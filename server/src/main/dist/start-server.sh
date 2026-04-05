#!/bin/bash
set -e  # Exit immediately if any command fails

echo "Starting Chatbot Server..."

# Ensure the script runs relative to its own location
cd "$(dirname "$0")"

# Apply default JVM memory settings only if JAVA_OPTS was not already provided
if [ -z "${JAVA_OPTS:-}" ]; then
    export JAVA_OPTS="-Xmx2G -Xms512M"
fi

echo "Launching server..."

# Replace the shell process with the Java process so signal handling works correctly
exec java $JAVA_OPTS -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain