#!/bin/bash
set -e  # Exit immediately if any command fails

echo "Starting Chatbot Server..."

# Ensure the script runs relative to its own location
cd "$(dirname "$0")"

# Apply default JVM memory settings only if JAVA_OPTS was not already provided
if [ -z "${JAVA_OPTS:-}" ]; then
    # Set default JVM options:
    # -Xmx2G: Set maximum heap size to 2GB
    # -Xms512M: Set initial heap size to 512MB
    # --enable-native-access=ALL-UNNAMED: Enable native access for all unnamed modules to allow loading native libraries (e.g. SQLite JDBC) without module system issues
    export JAVA_OPTS="-Xmx2G -Xms512M --enable-native-access=ALL-UNNAMED"
fi

echo "Launching server..."

# Replace the shell process with the Java process so signal handling works correctly
exec java $JAVA_OPTS -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain