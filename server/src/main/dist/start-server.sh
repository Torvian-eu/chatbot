#!/bin/bash
set -e  # Exit immediately if any command fails

echo "Starting Chatbot Server..."

# Ensure the script runs relative to its own location
cd "$(dirname "$0")"

# Make sure the runtime directories exist
mkdir -p config data logs

# Bootstrap application.json from default-config if missing
if [ ! -f "config/application.json" ]; then
    if [ ! -f "default-config/application.json" ]; then
        echo "Missing required default config: default-config/application.json"
        exit 1
    fi
    echo "application.json not found - copying default config"
    cp "default-config/application.json" "config/application.json"
fi

# Bootstrap setup.json from default-config if missing
if [ ! -f "config/setup.json" ]; then
    if [ ! -f "default-config/setup.json" ]; then
        echo "Missing required default config: default-config/setup.json"
        exit 1
    fi
    echo "setup.json not found - copying default setup config"
    cp "default-config/setup.json" "config/setup.json"
fi

# Bootstrap env-mapping.json from default-config if missing
if [ ! -f "config/env-mapping.json" ]; then
    if [ ! -f "default-config/env-mapping.json" ]; then
        echo "Missing required default config: default-config/env-mapping.json"
        exit 1
    fi
    echo "env-mapping.json not found - copying default environment mapping"
    cp "default-config/env-mapping.json" "config/env-mapping.json"
fi

# Apply default JVM memory settings only if JAVA_OPTS was not already provided
if [ -z "${JAVA_OPTS:-}" ]; then
    export JAVA_OPTS="-Xmx2G -Xms512M"
fi

echo "Launching server..."

# Replace the shell process with the Java process so signal handling works correctly
exec java $JAVA_OPTS -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain