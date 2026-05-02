@echo off
REM Chatbot Worker Startup Script

cd /d "%~dp0"

REM Set JVM options
set JAVA_OPTS=-Xmx2G -Xms512M

REM Run the worker
java %JAVA_OPTS% -cp "lib/*" eu.torvian.chatbot.worker.main.WorkerMain

