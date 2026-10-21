#!/bin/bash
set -e  # Exit immediately if any command fails

# --- 1. Dynamic User ID Re-mapping ---
# If PUID and PGID are passed as environment variables, we adjust the
# internal 'torvian' user to match the host user's ID.
if [ ! -z "${PUID}" ]; then
    USER_ID=${PUID}
    GROUP_ID=${PGID:-${PUID}}

    echo "Adapting torvian user to match host UID: $USER_ID, GID: $GROUP_ID"

    # Update the IDs (using -o to allow non-unique IDs if necessary)
    groupmod -o -g "$GROUP_ID" torvian || true
    usermod -o -u "$USER_ID" torvian || true
fi

# --- 2. Optional remap of the isolated account ---
# Only needed when the host's uid range collides with the image defaults: 'agent' is the account
# that commands spawned by built-in tools run as.
AGENT_HOME=/home/agent
if [ -n "${AGENT_UID}" ]; then
    groupmod -o -g "${AGENT_GID:-$AGENT_UID}" shared
    usermod -o -u "${AGENT_UID}" agent
fi

# --- 3. Fail fast when both accounts resolve to the same uid ---
# Isolation is impossible in that case, and a wrong mapping would be worse than a clear failure.
if [ "$(id -u torvian)" = "$(id -u agent)" ]; then
    echo "FATAL: 'torvian' and 'agent' resolve to the same uid ($(id -u torvian)); command isolation is impossible." >&2
    exit 1
fi

# New files are group-writable so the worker and the isolated account can share the workspace.
umask 0002

# --- 4. Secret-bearing directories: worker only ---
# These paths are usually mounted from the host, so the image's modes do not apply; re-applying
# them on every start is what keeps secrets.json and the token cache away from isolated commands.
chown -R torvian:torvian /app/config /app/data /app/logs
chmod 700 /app/config /app/data /app/logs

# --- 5. The isolated account's home ---
# A pre-existing volume mounted over /home has no /home/agent because Docker never repopulates an
# existing volume, while the worker derives the child's HOME as /home/<account>.
mkdir -p "$AGENT_HOME"
chown agent:shared "$AGENT_HOME"
chmod 0755 "$AGENT_HOME"

# Optional: repair the worker's own home, which a pre-populated /home volume keeps at the image's
# baked uid even after the PUID remap.
chown torvian:torvian /home/torvian 2>/dev/null || true

# --- 6. Shared workspace and caches ---
# Bind mounts and pre-existing named volumes keep their own owner, group and modes, so the isolated
# account may not be able to write into the workspace or a cache directory. The helpers below decide
# per shared root whether the expensive recursive repair has to run.

# Reports whether a shared directory still carries the ownership or group of an earlier state.
#
# The top-level directory is the sentinel so the recursive walk does not run on every start (caches
# can hold tens of thousands of files). A configured PUID/PGID remap is treated as a repair trigger
# too, because it leaves existing files owned by the previous uid.
needs_shared_repair() {
    local shared_root="$1"

    if [ "$(stat -c %g "$shared_root")" != "$SHARED_GID" ]; then
        return 0
    fi

    if [ -n "${PUID}" ] && [ "$(stat -c %u "$shared_root")" != "$(id -u torvian)" ]; then
        return 0
    fi

    return 1
}

# Normalizes one shared directory so both the worker and the isolated account can write in it.
#
# The recursive part runs only when [needs_shared_repair] asks for it; the top-level owner, group
# and setgid mode are re-applied on every start because they are cheap.
normalize_shared_root() {
    local shared_root="$1"

    if needs_shared_repair "$shared_root"; then
        echo "Normalizing shared group and modes under $shared_root ..."
        chown -R torvian:shared "$shared_root"
        find "$shared_root" -type d -exec chmod 2775 {} +
        find "$shared_root" -type f ! -perm -0020 -exec chmod g+w {} +
    fi

    chown torvian:shared "$shared_root"
    chmod 2775 "$shared_root"
}

# No account is named 'shared', so the gid comes from a group lookup; `id -g` would resolve the
# operand as a user and abort the entrypoint.
SHARED_GID="$(getent group shared | cut -d: -f3)"
if [ -z "$SHARED_GID" ]; then
    echo "FATAL: group 'shared' is missing from this image; command isolation cannot work." >&2
    exit 1
fi

for shared_root in /app/workspace /app/cache /app/cache/*; do
    [ -d "$shared_root" ] || continue
    normalize_shared_root "$shared_root"
done

# --- 7. pty4j native extraction directory ---
# Native libraries are loaded from this path, so it must be executable and must stay out of the
# shared workspace and cache, which are writable by the isolated account. A mounted volume may be
# empty or carry the wrong owner, so the directory is re-applied on every start.
PTY4J_TMPDIR=/app/data/tmp/pty4j
mkdir -p "$PTY4J_TMPDIR"
chown torvian:torvian "$PTY4J_TMPDIR"
chmod 0700 "$PTY4J_TMPDIR"

# Ensure applications started through gosu use the correct home directory.
# The isolated child's HOME is set by the worker, not by this shell.
export HOME=/home/torvian

echo "Starting Chatbot Worker..."

# Ensure the script runs relative to its own location
cd "$(dirname "$0")"

# Apply default JVM settings only if JAVA_OPTS was not already provided. Operators who override
# JAVA_OPTS take responsibility for keeping the pty4j temp dir and native-access flags.
if [ -z "${JAVA_OPTS:-}" ]; then
    export JAVA_OPTS="-Xmx2G -Xms512M -Dpty4j.tmpdir=$PTY4J_TMPDIR --enable-native-access=ALL-UNNAMED"
fi

echo "Launching worker process as torvian ($(id -u torvian))..."

# --- 8. Execute via gosu ---
# We use 'exec gosu' to replace the shell process with the Java process.
# This ensures that signals (SIGTERM) are passed directly to Java.
exec gosu torvian java $JAVA_OPTS -cp "lib/*" eu.torvian.chatbot.worker.main.WorkerMain "$@"
