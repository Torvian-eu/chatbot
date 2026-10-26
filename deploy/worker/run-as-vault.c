// run-as-vault: drop privileges to the credential account, then exec a command, signal a group, or
// create and remove secret files that only that account may read.
//
// Modes:
//   run-as-vault <command> [args...]                  create a process group, drop, then exec
//   run-as-vault --signal <TERM|KILL> <pgid>          drop, then signal a process group
//   run-as-vault --write-secret <root> <path>         read stdin into a new secret file
//   run-as-vault --clear-secret-dir <root> <dir>      empty one secret tree
//
// Build:   gcc -O2 -Wall -o run-as-vault run-as-vault.c
// Install: install -o root -g torvian -m 4750 run-as-vault /usr/local/bin/run-as-vault
//
// SECURITY
//  - The target account is hardcoded below. Never accept a UID/GID from argv or the environment.
//  - The helper must only ever LOWER privileges, and it verifies the drop before it acts.
//  - The two secret modes exist because the worker JVM runs unprivileged: it cannot chown a file to
//    this account and cannot unlink inside a directory this account owns. Both modes take the secret
//    root as an argument instead of a compiled-in constant, so one setting decides where secrets
//    live; passing it grants nothing, because only the worker group may execute this helper and the
//    worker already chooses every child's argv.
//  - The clear mode only empties a tree and leaves the directory itself to its caller: removing an
//    entry inside the secret root needs write permission there, and the root is owned by the worker
//    account with mode 0711, so this account can only traverse it.
//  - Both modes refuse a target that is not a real descendant of the root, which stops a worker bug
//    from writing a secret outside the tree. O_EXCL additionally turns a pre-planted file or symlink
//    into a hard failure instead of a write into someone else's file.
//  - The helper only replaces its own process image or delivers a signal, so the parent stays the
//    single authority over what the child can see.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pwd.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "privilege_drop.h"

#define VAULT_USER "vault"
#define PROGRAM "run-as-vault"

#define SIGNAL_MODE "--signal"
#define WRITE_SECRET_MODE "--write-secret"
#define CLEAR_SECRET_MODE "--clear-secret-dir"

// Upper bound on a secret read from stdin. Matches the largest worker-side limit, so a truncated
// secret is always a hard failure rather than a silently shortened credential.
#define MAX_SECRET_BYTES (1024 * 1024)

// Exit codes. The first group matches run-as-agent so a caller can classify both helpers the same
// way; 13 and 14 exist only here.
#define EXIT_USAGE 2
#define EXIT_NO_ACCOUNT 3
#define EXIT_PRCTL 8
#define EXIT_EXEC 9
#define EXIT_SETPGID 10
#define EXIT_SIGNAL 11
#define EXIT_BAD_ARGUMENT 12
#define EXIT_BAD_PATH 13
#define EXIT_IO 14

// Maps the signal name the worker sends. Only the two signals the cleanup path uses are accepted,
// so a caller cannot pick a signal with a different default disposition.
static int parse_signal(const char *name) {
    if (strcmp(name, "TERM") == 0) return SIGTERM;
    if (strcmp(name, "KILL") == 0) return SIGKILL;
    return -1;
}

// Reports whether `path` contains a ".." component. Both secret modes reject those outright so the
// descendant check below cannot be defeated by a path that only looks contained.
static int has_dotdot_component(const char *path) {
    const char *cursor = path;
    while (*cursor != '\0') {
        while (*cursor == '/') cursor++;
        const char *component = cursor;
        while (*cursor != '\0' && *cursor != '/') cursor++;
        size_t length = (size_t)(cursor - component);
        if (length == 2 && component[0] == '.' && component[1] == '.') return 1;
    }
    return 0;
}

// Reports whether `candidate` is a strict descendant of `root`. Both must already be absolute and
// free of symlinks, which the callers guarantee by comparing realpath output.
static int is_strict_descendant(const char *root, const char *candidate) {
    size_t root_length = strlen(root);
    if (strncmp(candidate, root, root_length) != 0) return 0;
    if (candidate[root_length] != '/') return 0;
    return candidate[root_length + 1] != '\0';
}

// Resolves the secret root to a real path, refusing anything that is not an existing absolute
// directory without ".." components.
static int resolve_root(const char *root, char *resolved, size_t resolved_size) {
    if (root[0] != '/' || has_dotdot_component(root)) {
        fprintf(stderr, "%s: %s is not an acceptable secret root\n", PROGRAM, root);
        return EXIT_BAD_PATH;
    }
    if (realpath(root, resolved) == NULL) {
        fprintf(stderr, "%s: secret root %s: %m\n", PROGRAM, root);
        return EXIT_BAD_PATH;
    }
    if (resolved[0] == '\0' || strcmp(resolved, "/") == 0) {
        fprintf(stderr, "%s: %s must not be the filesystem root\n", PROGRAM, root);
        return EXIT_BAD_PATH;
    }
    return 0;
}

// Creates every missing directory between the secret root and `parent` as root: mode 0700, owned by
// the credential account. Creating them before the drop is what makes the resulting tree unreachable
// for any other account, including the one ordinary commands run as.
static int create_missing_dirs(const char *root, const char *parent, const struct passwd *pw) {
    char current[PATH_MAX];
    if (snprintf(current, sizeof(current), "%s", root) >= (int)sizeof(current)) return EXIT_IO;

    const char *rest = parent + strlen(root);
    while (*rest == '/') rest++;

    while (*rest != '\0') {
        const char *slash = strchr(rest, '/');
        size_t component_length = slash != NULL ? (size_t)(slash - rest) : strlen(rest);

        size_t current_length = strlen(current);
        if (current_length + 1 + component_length + 1 > sizeof(current)) {
            fprintf(stderr, "%s: path too long: %s\n", PROGRAM, parent);
            return EXIT_IO;
        }
        snprintf(current + current_length, sizeof(current) - current_length, "/%.*s",
                 (int)component_length, rest);

        if (mkdir(current, 0700) != 0) {
            if (errno != EEXIST) {
                fprintf(stderr, "%s: mkdir %s: %m\n", PROGRAM, current);
                return EXIT_IO;
            }
            // An existing entry must be a real directory: a symlink here would redirect the write
            // outside the secret root even though the path string looks contained.
            struct stat entry;
            if (lstat(current, &entry) != 0 || !S_ISDIR(entry.st_mode)) {
                fprintf(stderr, "%s: %s is not a directory\n", PROGRAM, current);
                return EXIT_BAD_PATH;
            }
        } else if (lchown(current, pw->pw_uid, pw->pw_gid) != 0) {
            fprintf(stderr, "%s: chown %s: %m\n", PROGRAM, current);
            return EXIT_IO;
        }

        rest = slash != NULL ? slash + 1 : rest + component_length;
    }
    return 0;
}

// Removes one tree without following symlinks. Missing entries count as success: the worker asks for
// a cleanup it cannot always know the state of.
//
// `remove_self` is 0 for the tree root, whose parent is the secret root: the credential account has
// traverse-only permission there, so removing that entry always fails with EACCES and the caller
// removes the emptied directory with the worker account's authority instead.
static int remove_tree(const char *path, int remove_self) {
    struct stat entry;
    if (lstat(path, &entry) != 0) {
        if (errno == ENOENT) return 0;
        fprintf(stderr, "%s: %s: %m\n", PROGRAM, path);
        return EXIT_IO;
    }

    if (!S_ISDIR(entry.st_mode)) {
        // A regular file that sits where a tree root was expected is the caller's to remove for the
        // same reason the root directory is.
        if (!remove_self) return 0;
        if (unlink(path) != 0 && errno != ENOENT) {
            fprintf(stderr, "%s: unlink %s: %m\n", PROGRAM, path);
            return EXIT_IO;
        }
        return 0;
    }

    DIR *directory = opendir(path);
    if (directory == NULL) {
        fprintf(stderr, "%s: opendir %s: %m\n", PROGRAM, path);
        return EXIT_IO;
    }

    int result = 0;
    struct dirent *child;
    while ((child = readdir(directory)) != NULL) {
        if (strcmp(child->d_name, ".") == 0 || strcmp(child->d_name, "..") == 0) continue;

        char child_path[PATH_MAX];
        if (snprintf(child_path, sizeof(child_path), "%s/%s", path, child->d_name)
            >= (int)sizeof(child_path)) {
            fprintf(stderr, "%s: path too long under %s\n", PROGRAM, path);
            result = EXIT_IO;
            break;
        }
        int child_result = remove_tree(child_path, 1);
        if (child_result != 0) result = child_result;
    }
    closedir(directory);

    // The directory itself is removed even when one child failed, so a single unreadable entry does
    // not keep the whole tree alive.
    if (remove_self && rmdir(path) != 0 && errno != ENOENT && result == 0) {
        fprintf(stderr, "%s: rmdir %s: %m\n", PROGRAM, path);
        result = EXIT_IO;
    }
    return result;
}

// Writes `real_root` and the part of `path` that follows `root` into `out`.
//
// The root may itself be a symlink, so the suffix is taken from the configured root and re-attached
// to its resolved form: the paths the helper creates are then always inside the tree it validated.
static int resolve_against_root(
    const char *root,
    const char *real_root,
    const char *path,
    char *out,
    size_t out_size
) {
    size_t root_length = strlen(root);
    if (strcmp(path, root) == 0) {
        // Writing directly into the root is allowed; removing the root itself is not, which the
        // strict-descendant check leaves to the caller.
        if (snprintf(out, out_size, "%s", real_root) >= (int)out_size) return EXIT_IO;
        return 0;
    }
    if (strncmp(path, root, root_length) != 0 || path[root_length] != '/') {
        fprintf(stderr, "%s: %s is outside %s\n", PROGRAM, path, root);
        return EXIT_BAD_PATH;
    }
    if (snprintf(out, out_size, "%s%s", real_root, path + root_length) >= (int)out_size) {
        fprintf(stderr, "%s: path too long: %s\n", PROGRAM, path);
        return EXIT_IO;
    }
    return 0;
}

// Creates the secret file named by [path] inside the secret root [root] and fills it from stdin.
static int write_secret(const char *root, const char *path) {
    char real_root[PATH_MAX];
    int root_result = resolve_root(root, real_root, sizeof(real_root));
    if (root_result != 0) return root_result;

    if (path[0] != '/' || has_dotdot_component(path)) {
        fprintf(stderr, "%s: %s is not an acceptable secret path\n", PROGRAM, path);
        return EXIT_BAD_PATH;
    }

    // The file itself does not exist yet, so the parent is what has to be resolved.
    char parent[PATH_MAX];
    if (snprintf(parent, sizeof(parent), "%s", path) >= (int)sizeof(parent)) return EXIT_BAD_PATH;
    char *last_slash = strrchr(parent, '/');
    if (last_slash == NULL || last_slash == parent) {
        fprintf(stderr, "%s: %s has no parent directory\n", PROGRAM, path);
        return EXIT_BAD_PATH;
    }
    const char *file_name = last_slash + 1;
    if (*file_name == '\0' || strcmp(file_name, ".") == 0 || strcmp(file_name, "..") == 0) {
        fprintf(stderr, "%s: %s does not name a file\n", PROGRAM, path);
        return EXIT_BAD_PATH;
    }
    *last_slash = '\0';

    struct passwd *pw = getpwnam(VAULT_USER);
    if (pw == NULL) {
        fprintf(stderr, "%s: getpwnam: %m\n", PROGRAM);
        return EXIT_NO_ACCOUNT;
    }

    char resolved_parent[PATH_MAX];
    int path_result = resolve_against_root(root, real_root, parent, resolved_parent, sizeof(resolved_parent));
    if (path_result != 0) return path_result;

    int create_result = create_missing_dirs(real_root, resolved_parent, pw);
    if (create_result != 0) return create_result;

    char real_parent[PATH_MAX];
    if (realpath(resolved_parent, real_parent) == NULL) {
        fprintf(stderr, "%s: %s: %m\n", PROGRAM, resolved_parent);
        return EXIT_IO;
    }

    char target[PATH_MAX];
    if (snprintf(target, sizeof(target), "%s/%s", real_parent, file_name) >= (int)sizeof(target)) {
        return EXIT_IO;
    }
    if (!is_strict_descendant(real_root, target)) {
        fprintf(stderr, "%s: %s is outside the secret root %s\n", PROGRAM, target, real_root);
        return EXIT_BAD_PATH;
    }

    int drop_result = drop_privileges(PROGRAM, pw);
    if (drop_result != 0) return drop_result;

    umask(0077);
    int fd = open(target, O_CREAT | O_EXCL | O_NOFOLLOW | O_WRONLY, 0400);
    if (fd < 0) {
        fprintf(stderr, "%s: open %s: %m\n", PROGRAM, target);
        return EXIT_IO;
    }

    char buffer[4096];
    size_t total = 0;
    for (;;) {
        ssize_t read_count = read(STDIN_FILENO, buffer, sizeof(buffer));
        if (read_count < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "%s: read: %m\n", PROGRAM);
            close(fd);
            unlink(target);
            return EXIT_IO;
        }
        if (read_count == 0) break;

        total += (size_t)read_count;
        if (total > MAX_SECRET_BYTES) {
            fprintf(stderr, "%s: secret exceeds %d bytes\n", PROGRAM, MAX_SECRET_BYTES);
            close(fd);
            unlink(target);
            return EXIT_IO;
        }

        ssize_t written = 0;
        while (written < read_count) {
            ssize_t write_count = write(fd, buffer + written, (size_t)(read_count - written));
            if (write_count < 0) {
                if (errno == EINTR) continue;
                fprintf(stderr, "%s: write %s: %m\n", PROGRAM, target);
                close(fd);
                unlink(target);
                return EXIT_IO;
            }
            written += write_count;
        }
    }

    if (close(fd) != 0) {
        fprintf(stderr, "%s: close %s: %m\n", PROGRAM, target);
        unlink(target);
        return EXIT_IO;
    }
    return 0;
}

// Empties the secret tree named by [path], which must be a strict descendant of [root]. The
// directory itself stays in place: it is the caller that removes it, under the account that owns the
// secret root.
static int clear_secret_dir(const char *root, const char *path) {
    char real_root[PATH_MAX];
    int root_result = resolve_root(root, real_root, sizeof(real_root));
    if (root_result != 0) return root_result;

    if (path[0] != '/' || has_dotdot_component(path)) {
        fprintf(stderr, "%s: %s is not an acceptable secret path\n", PROGRAM, path);
        return EXIT_BAD_PATH;
    }

    struct passwd *pw = getpwnam(VAULT_USER);
    if (pw == NULL) {
        fprintf(stderr, "%s: getpwnam: %m\n", PROGRAM);
        return EXIT_NO_ACCOUNT;
    }

    // A tree that is already gone needs no removal, and that is the normal outcome of asking twice.
    char target[PATH_MAX];
    int path_result = resolve_against_root(root, real_root, path, target, sizeof(target));
    if (path_result != 0) return path_result;

    struct stat entry;
    if (lstat(target, &entry) != 0) {
        if (errno == ENOENT) return 0;
        fprintf(stderr, "%s: %s: %m\n", PROGRAM, target);
        return EXIT_IO;
    }

    char real_path[PATH_MAX];
    if (realpath(target, real_path) == NULL) {
        if (errno == ENOENT) return 0;
        fprintf(stderr, "%s: %s: %m\n", PROGRAM, target);
        return EXIT_IO;
    }
    if (!is_strict_descendant(real_root, real_path)) {
        fprintf(stderr, "%s: %s is outside the secret root %s\n", PROGRAM, real_path, real_root);
        return EXIT_BAD_PATH;
    }

    int drop_result = drop_privileges(PROGRAM, pw);
    if (drop_result != 0) return drop_result;

    // Nothing privileged runs after the drop: the only work left is unlinking inside the tree, which
    // the credential account owns.
    return remove_tree(real_path, 0);
}

// Signals one process group as the credential account. Exit 0 also covers "the group no longer
// exists": an empty group is the normal result of the second termination attempt per cleanup.
static int signal_group(const char *signal_name, const char *process_group_id) {
    int signal_number = parse_signal(signal_name);
    if (signal_number < 0) {
        fprintf(stderr, "%s: unsupported signal '%s'\n", PROGRAM, signal_name);
        return EXIT_BAD_ARGUMENT;
    }

    char *end = NULL;
    long pgid = strtol(process_group_id, &end, 10);
    if (end == process_group_id || *end != '\0' || pgid <= 1 || pgid > INT_MAX) {
        fprintf(stderr, "%s: invalid process group id '%s'\n", PROGRAM, process_group_id);
        return EXIT_BAD_ARGUMENT;
    }

    // A negative pid addresses the whole group, which also reaches descendants that were re-parented
    // to init, as long as they stayed in the group.
    if (kill(-(pid_t)pgid, signal_number) != 0) {
        if (errno == ESRCH) return 0;
        fprintf(stderr, "%s: kill: %m\n", PROGRAM);
        return EXIT_SIGNAL;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: run-as-vault <command> [args...]\n");
        fprintf(stderr, "       run-as-vault --signal <TERM|KILL> <pgid>\n");
        fprintf(stderr, "       run-as-vault --write-secret <root> <path>\n");
        fprintf(stderr, "       run-as-vault --clear-secret-dir <root> <dir>\n");
        return EXIT_USAGE;
    }

    // The mode is decided first, because the process group and the no-new-privileges flag below
    // exist only where an operator command is executed.
    int secret_write_mode = strcmp(argv[1], WRITE_SECRET_MODE) == 0;
    int secret_clear_mode = strcmp(argv[1], CLEAR_SECRET_MODE) == 0;
    int signal_mode = strcmp(argv[1], SIGNAL_MODE) == 0;

    if (secret_write_mode) {
        if (argc != 4) {
            fprintf(stderr, "usage: run-as-vault --write-secret <root> <path>\n");
            return EXIT_USAGE;
        }
        return write_secret(argv[2], argv[3]);
    }

    if (secret_clear_mode) {
        if (argc != 4) {
            fprintf(stderr, "usage: run-as-vault --clear-secret-dir <root> <dir>\n");
            return EXIT_USAGE;
        }
        return clear_secret_dir(argv[2], argv[3]);
    }

    // Give the command its own process group before the account lookup: the worker terminates a
    // re-parented tree by signalling this group, and a stalled lookup would otherwise let the relay
    // report a not-yet-existing group as a delivered signal, after which the command starts and
    // outlives its timeout. Creating the group lowers no privilege and adds no authority.
    //
    // pty4j's child calls setsid() before it execs this helper, so the helper can already be a
    // session and process-group leader. Linux rejects setpgid(0,0) on a session leader with EPERM,
    // and that state is exactly what the group-signalling contract needs, so it is success here.
    if (!signal_mode) {
        if (getpgid(0) != getpid()) {
            if (setpgid(0, 0) != 0) {
                fprintf(stderr, "%s: setpgid: %m\n", PROGRAM);
                return EXIT_SETPGID;
            }
        }
    }

    struct passwd *pw = getpwnam(VAULT_USER);
    if (pw == NULL) {
        fprintf(stderr, "%s: getpwnam: %m\n", PROGRAM);
        return EXIT_NO_ACCOUNT;
    }

    int drop_result = drop_privileges(PROGRAM, pw);
    if (drop_result != 0) return drop_result;

    if (signal_mode) {
        if (argc != 4) {
            fprintf(stderr, "usage: run-as-vault --signal <TERM|KILL> <pgid>\n");
            return EXIT_USAGE;
        }
        return signal_group(argv[2], argv[3]);
    }

    // The child is unprivileged; make that irreversible so it cannot use setuid binaries later.
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        fprintf(stderr, "%s: prctl: %m\n", PROGRAM);
        return EXIT_PRCTL;
    }

    // Files this command creates stay owner-only even though its primary group is the shared one, so
    // a command writing into its working directory does not expose its output to the workspace group.
    umask(0077);

    // argv[1] onwards is the caller's command; argv[0] becomes the command name.
    execvp(argv[1], &argv[1]);
    fprintf(stderr, "%s: execvp: %m\n", PROGRAM);
    return EXIT_EXEC;
}
