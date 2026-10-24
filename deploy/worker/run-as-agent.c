// run-as-agent: drop privileges to the isolated account, then exec a command or signal a group.
//
// Modes:
//   run-as-agent <command> [args...]           create a process group, drop privileges, then exec
//   run-as-agent --signal <TERM|KILL> <pgid>   drop privileges, then signal a process group
//
// Build:   gcc -O2 -Wall -o run-as-agent run-as-agent.c
// Install: install -o root -g torvian -m 4750 run-as-agent /usr/local/bin/run-as-agent
//
// SECURITY
//  - The target account is hardcoded below. Never accept a UID/GID from argv or the
//    environment: any caller could then ask the helper to become root.
//  - The helper must only ever LOWER privileges, and it verifies the drop before it acts.
//  - The signal mode takes only a process-group id and one of two signal names, and it can reach
//    only processes the isolated account could signal itself. A pid is not a uid: the two rules
//    above are unaffected by the mode, and the mode adds no authority.
//  - The caller chooses the environment; this helper only replaces its own process image or
//    delivers a signal, so the parent stays the single authority over what the child can see.
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <pwd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/prctl.h>

#include "privilege_drop.h"

#define ISOLATED_USER "agent"
#define PROGRAM "run-as-agent"
#define SIGNAL_MODE "--signal"

// Maps the signal name the worker sends. Only the two signals the cleanup path uses are accepted,
// so a caller cannot pick a signal with a different default disposition.
static int parse_signal(const char *name) {
    if (strcmp(name, "TERM") == 0) return SIGTERM;
    if (strcmp(name, "KILL") == 0) return SIGKILL;
    return -1;
}

// Signals one process group as the isolated account. Exit 0 also covers "the group no longer
// exists": an empty group is the normal result of the second termination attempt per cleanup, so it
// must not be reported as a failure.
static int signal_group(const char *signal_name, const char *process_group_id) {
    int signal_number = parse_signal(signal_name);
    if (signal_number < 0) {
        fprintf(stderr, "run-as-agent: unsupported signal '%s'\n", signal_name);
        return 12;
    }

    char *end = NULL;
    long pgid = strtol(process_group_id, &end, 10);
    if (end == process_group_id || *end != '\0' || pgid <= 1 || pgid > INT_MAX) {
        fprintf(stderr, "run-as-agent: invalid process group id '%s'\n", process_group_id);
        return 12;
    }

    // A negative pid addresses the whole group, which also reaches descendants that were re-parented
    // to init, as long as they stayed in the group.
    if (kill(-(pid_t)pgid, signal_number) != 0) {
        if (errno == ESRCH) return 0;
        perror("run-as-agent: kill");
        return 11;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: run-as-agent <command> [args...]\n");
        fprintf(stderr, "       run-as-agent --signal <TERM|KILL> <pgid>\n");
        return 2;
    }

    // The mode is decided first, because the group below exists only in exec mode.
    int signal_mode = strcmp(argv[1], SIGNAL_MODE) == 0;

    // Give the command its own process group before the account lookup: the worker terminates a
    // re-parented tree by signalling this group, and a stalled lookup would otherwise let the relay
    // report a not-yet-existing group as a delivered signal, after which the command starts and
    // outlives its timeout. Creating the group lowers no privilege and adds no authority.
    //
    // pty4j's child calls setsid() before it execs this helper, so the helper can already be a
    // session and process-group leader. Linux rejects setpgid(0,0) on a session leader with EPERM,
    // and that state is exactly what the group-signalling contract needs, so it is success here.
    // The command's group id remains the direct child's pid, which is what signal mode addresses.
    if (!signal_mode) {
        if (getpgid(0) != getpid()) {
            if (setpgid(0, 0) != 0) { perror("run-as-agent: setpgid"); return 10; }
        }
    }

    struct passwd *pw = getpwnam(ISOLATED_USER);
    if (pw == NULL) {
        perror("run-as-agent: getpwnam");
        return 3;
    }

    int drop_result = drop_privileges(PROGRAM, pw);
    if (drop_result != 0) return drop_result;

    if (signal_mode) {
        if (argc != 4) {
            fprintf(stderr, "usage: run-as-agent --signal <TERM|KILL> <pgid>\n");
            return 2;
        }
        return signal_group(argv[2], argv[3]);
    }

    // The child is unprivileged; make that irreversible so it cannot use setuid binaries later.
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        perror("run-as-agent: prctl");
        return 8;
    }

    // argv[1] onwards is the caller's command; argv[0] becomes the command name.
    execvp(argv[1], &argv[1]);
    perror("run-as-agent: execvp");
    return 9;
}
