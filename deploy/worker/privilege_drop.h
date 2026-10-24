// privilege_drop.h: the privilege-drop sequence shared by the worker's setuid helpers.
//
// Included by both run-as-agent.c and run-as-vault.c so the security-critical part - dropping every
// group, then the group id, then the user id, and verifying that all three took effect - exists in
// exactly one place. The account itself is not shared: each helper hardcodes its own, because an
// account taken from argv or the environment would let any caller ask the helper to become root.
//
// The helper must only ever LOWER privileges, and it verifies the drop before it acts.
#ifndef TORVIAN_PRIVILEGE_DROP_H
#define TORVIAN_PRIVILEGE_DROP_H

#include <grp.h>
#include <pwd.h>
#include <stdio.h>
#include <unistd.h>

// Drops every credential the caller may hold and refuses to continue unless the drop fully took
// effect (defence against ordering bugs).
//
// Returns 0 on success. On failure it reports the failing call on stderr and returns the documented
// exit code for that step: 4 setgroups, 5 setgid, 6 setuid, 7 verification.
static int drop_privileges(const char *program, const struct passwd *pw) {
    // Drop every supplementary group first: the caller's groups must not leak into the child.
    // The isolated accounts keep 'shared' as their primary group, so the workspace stays usable.
    if (setgroups(0, NULL) != 0) {
        fprintf(stderr, "%s: setgroups: %m\n", program);
        return 4;
    }
    if (setgid(pw->pw_gid) != 0) {
        fprintf(stderr, "%s: setgid: %m\n", program);
        return 5;
    }
    if (setuid(pw->pw_uid) != 0) {
        fprintf(stderr, "%s: setuid: %m\n", program);
        return 6;
    }

    if (getuid() != pw->pw_uid || geteuid() != pw->pw_uid || getgid() != pw->pw_gid) {
        fprintf(stderr, "%s: privilege drop failed\n", program);
        return 7;
    }
    return 0;
}

#endif
