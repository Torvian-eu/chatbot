#!/bin/bash
# Start Chatbot GUI with terminal window to view log output
# This script launches the application and keeps the terminal open to display logs

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Change to the script directory
cd "$SCRIPT_DIR"

# Run the application
./Chatbot

# Keep terminal open after the application closes (optional)
# Uncomment the following line if you want the terminal to stay open:
# read -p "Press Enter to close this window..."

