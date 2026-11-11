#include <stdio.h>
#include <dlfcn.h>

#include <algorithm>
#include <iostream>
#include <memory>

#include "kallsyms.hpp"
#include "log.hpp"
#include "selinux.hpp"
#include "utils.hpp"

bool switch_to_init_selinux_context()
{
	void* handle = dlopen("libselinux.so", RTLD_NOW | RTLD_LOCAL);
	if (handle == nullptr) {
		log_info("Could not load libselinux for context switch: %s", dlerror());
		return false;
	}

	dlerror();
	using Setcon = int (*)(const char*);
	auto setcon_fn = reinterpret_cast<Setcon>(dlsym(handle, "setcon"));
	const char* symbol_error = dlerror();
	if (symbol_error != nullptr || setcon_fn == nullptr) {
		log_info("Could not resolve setcon: %s", symbol_error == nullptr ? "unknown error" : symbol_error);
		dlclose(handle);
		return false;
	}

	errno = 0;
	const int result = setcon_fn("u:r:init:s0");
	const int saved_errno = errno;
	dlclose(handle);
	if (result != 0) {
		log_info("Could not switch SELinux context to init: %s", strerror(saved_errno));
		return false;
	}

	log_info("Switched SELinux context to u:r:init:s0");
	return true;
}

bool is_selinux_enforcing(bool& is_enforcing)
{
	const char* enforce_path = "/sys/fs/selinux/enforce";
	uint8_t enforcing_val = 0;

	FILE* enforce_file = fopen(enforce_path, "r");
	if (enforce_file == nullptr) {
		log_info("Could not read SELinux enforce file: %s", strerror(errno));
		return false;
	}

	size_t bytes_read = fread(&enforcing_val, 1, sizeof(enforcing_val), enforce_file);
	if (bytes_read != sizeof(enforcing_val)) {
		log_error("Expected to read %d bytes but got %d", sizeof(enforcing_val), bytes_read);
		return false;
	}

	int ret = fclose(enforce_file);
	if (ret != 0) {
		log_error("Failed to close the SELinux enforce file: %s", strerror(errno));
		return false;
	}

	is_enforcing = enforcing_val == '1';

	return true;
}

bool set_selinux_permissive(MTKSu& kern_rw, std::map<std::string, uint64_t>& symbol_table)
{
	std::string sel_read_enforce = "tsel_read_enforce";
	uint64_t sel_read_enforce_addr = 0;

	try {
		sel_read_enforce_addr = symbol_table.at(sel_read_enforce);
	} catch (std::out_of_range e) {
		log_error("Could not find the %s kernel symbol", sel_read_enforce.c_str());
		return false;
	}
	log_info("Found sel_read_enforce at 0x%" PRIx64, sel_read_enforce_addr);

	// Do not scan into neighboring functions or their literal pools. Kallsyms only
	// contains text symbols on this build, so the next symbol bounds this function.
	uint64_t sel_read_enforce_end = sel_read_enforce_addr + 0x100;
	for (const auto& symbol : symbol_table) {
		if (symbol.second > sel_read_enforce_addr && symbol.second < sel_read_enforce_end) {
			sel_read_enforce_end = symbol.second;
		}
	}
	const uint32_t read_buf_size = static_cast<uint32_t>(sel_read_enforce_end - sel_read_enforce_addr);
	if (read_buf_size < sizeof(uint32_t)) {
		log_error("sel_read_enforce has an invalid runtime size: %u", read_buf_size);
		return false;
	}
	log_info("Scanning sel_read_enforce runtime range 0x%" PRIx64 "-0x%" PRIx64,
	         sel_read_enforce_addr, sel_read_enforce_end);

	auto read_buf = std::make_unique<std::vector<uint8_t>>();
	auto success = kern_rw.read(sel_read_enforce_addr, read_buf_size, read_buf);
	if (!success) {
		log_error("Failed to read sel_read_enforce_addr function");
		return false;
	}

	// This ARMv7 kernel is built as Thumb-2. Absolute data addresses are materialized
	// as MOVW (low half) + MOVT (high half), then dereferenced with LDR. Decode that
	// sequence instead of treating arbitrary words in and after the function as data
	// pointers.
	uint16_t movw_low[16] = {};
	uint32_t movw_at[16] = {};
	bool have_movw[16] = {};
	uint64_t selinux_enforcing_ptr = 0;

	auto dereferences_register = [&](uint32_t start_offset, uint32_t reg) {
		const uint32_t stop = std::min(read_buf_size, start_offset + 0x30);
		for (uint32_t offset = start_offset; offset + 2 <= stop; offset += 2) {
			uint16_t halfword = 0;
			get_value<uint16_t>(*read_buf, offset, halfword);
			// 16-bit LDR Rt, [Rn, #0].
			if ((halfword & 0xf800) == 0x6800 && ((halfword >> 3) & 7) == reg &&
			    ((halfword >> 6) & 0x1f) == 0) {
				return true;
			}
			if (offset + 4 <= stop) {
				uint16_t second_halfword = 0;
				get_value<uint16_t>(*read_buf, offset + 2, second_halfword);
				// 32-bit LDR.W Rt, [Rn, #0].
				if ((halfword & 0xfff0) == 0xf8d0 && (halfword & 0xf) == reg &&
				    (second_halfword & 0x0fff) == 0) {
					return true;
				}
			}
		}
		return false;
	};

	auto candidate_is_enforcing = [&](uint32_t candidate, uint64_t reference_addr) {
		if (!is_kernel_address(candidate)) {
			return false;
		}
		auto value_buf = std::make_unique<std::vector<uint8_t>>();
		if (!kern_rw.read(candidate, sizeof(uint32_t), value_buf)) {
			return false;
		}
		uint32_t value = 0;
		if (!get_value<uint32_t>(*value_buf, 0, value)) {
			return false;
		}
		log_info("Decoded data candidate 0x%x from 0x%" PRIx64 ": value=%u", candidate,
		         reference_addr, value);
		if (value != 1) {
			return false;
		}
		selinux_enforcing_ptr = candidate;
		return true;
	};

	for (uint32_t offset = 0; offset + 4 <= read_buf_size && selinux_enforcing_ptr == 0; offset += 2) {
		uint16_t first_halfword = 0;
		uint16_t second_halfword = 0;
		get_value<uint16_t>(*read_buf, offset, first_halfword);
		get_value<uint16_t>(*read_buf, offset + 2, second_halfword);

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
		if (!have_movw[reg] || offset - movw_at[reg] > 0x30 ||
		    !dereferences_register(offset + 4, reg)) {
			continue;
		}

		const uint32_t candidate = (static_cast<uint32_t>(immediate) << 16) | movw_low[reg];
		candidate_is_enforcing(candidate, sel_read_enforce_addr + movw_at[reg]);
	}

	if (selinux_enforcing_ptr == 0) {
		log_error("Could not decode selinux_enforcing from sel_read_enforce");
		return false;
	}
	log_info("Found selinux_enforcing at 0x%" PRIx64, selinux_enforcing_ptr);

	auto write_buf = std::make_unique<std::vector<uint8_t>>(sizeof(uint32_t), 0);
	success = kern_rw.write(selinux_enforcing_ptr, write_buf);
	if (!success) {
		log_error("Failed to set selinux_enforcing to permissive");
		return false;
	}

	auto verify_buf = std::make_unique<std::vector<uint8_t>>();
	success = kern_rw.read(selinux_enforcing_ptr, sizeof(uint32_t), verify_buf);
	uint32_t verify_value = 1;
	if (!success || !get_value<uint32_t>(*verify_buf, 0, verify_value) || verify_value != 0) {
		log_error("SELinux permissive write verification failed");
		return false;
	}
	log_info("Verified selinux_enforcing=0");
	return true;
}
