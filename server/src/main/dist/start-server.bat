@echo off
REM Chatbot Server Startup Script

cd /d "%~dp0"

REM Check for config files
if not exist "config\application.json" (
    echo Configuration file not found: config\application.json
    echo Please copy config files from templates and configure.
    exit /b 1
)

REM Set JVM options
set JAVA_OPTS=-Xmx2G -Xms512M

REM Run the server
java %JAVA_OPTS% -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain

