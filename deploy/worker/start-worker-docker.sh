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

    # Fix permissions on writable directories to ensure the new UID owns them
    # This is vital for app functionality
    chown -R torvian:torvian /app/config /app/data /app/logs /app/cache
fi

echo "Starting Chatbot Worker..."

# Ensure the script runs relative to its own location
cd "$(dirname "$0")"

# Apply default JVM memory settings only if JAVA_OPTS was not already provided
if [ -z "${JAVA_OPTS:-}" ]; then
    export JAVA_OPTS="-Xmx2G -Xms512M"
fi

echo "Launching worker process as torvian ($(id -u torvian))..."

# --- 2. Execute via gosu ---
# We use 'exec gosu' to replace the shell process with the Java process.
# This ensures that signals (SIGTERM) are passed directly to Java.
exec gosu torvian java $JAVA_OPTS -cp "lib/*" eu.torvian.chatbot.worker.main.WorkerMain