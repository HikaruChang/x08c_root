#ifndef TASK_HPP
#define TASK_HPP

#include <map>
#include <memory>
#include <string>

#include "mtk_su.hpp"

/**
 * Escalate the process credentials to root.
 *
 * @param kern_rw: The kernel read/write context used to read/write kernel memory.
 * @param symbol_table: The kernel symbol table.
 * @return true if the task_struct was found, otherwise false
 */
bool escalate_creds(MTKSu& kern_rw, std::map<std::string, uint64_t>& symbol_table);

/** Replace both credential pointers for the named process with init_cred. */
bool escalate_task_by_command_name(MTKSu& kern_rw,
                                   std::map<std::string, uint64_t>& symbol_table,
                                   const std::string& command_name);

#endif  // TASK_HPP
