@echo off
REM Chatbot Server Startup Script

cd /d "%~dp0"

REM Set JVM options
set JAVA_OPTS=-Xmx2G -Xms512M

REM Run the server
java %JAVA_OPTS% -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain

