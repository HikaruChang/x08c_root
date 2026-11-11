#ifndef SELINUX_HPP
#define SELINUX_HPP

#include <map>
#include <memory>
#include <vector>

#include "mtk_su.hpp"

/**
 * Determines if SELinux status is set to enforcing. If not, it's permissive.
 *
 * @param is_enforcing: True if SELinux is enforcing, otherwise false for permissive
 * @return true if the SELinux status was successfully resolved, otherwise false on error
 */
bool is_selinux_enforcing(bool& is_enforcing);

bool set_selinux_permissive(MTKSu& kern_rw, std::map<std::string, uint64_t>& symbol_table);

/** Switch this already-root, permissive process to init's SELinux domain so
 * Android's property service accepts the narrowly scoped ADB properties used
 * by the post-root script. This is best-effort; SSH does not depend on it. */
bool switch_to_init_selinux_context();

#endif  // SELINUX_HPP
