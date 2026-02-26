#!/bin/bash
# Chatbot Server Startup Script

cd "$(dirname "$0")"

# Check for config files
if [ ! -f "config/application.json" ]; then
    echo "Configuration file not found: config/application.json"
    echo "Please copy config files from templates and configure."
    exit 1
fi

# Set JVM options
export JAVA_OPTS="-Xmx2G -Xms512M"

# Run the server
java $JAVA_OPTS -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain

