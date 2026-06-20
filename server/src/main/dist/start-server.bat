@echo off
REM Chatbot Server Startup Script

cd /d "%~dp0"

REM Set JVM options:
REM - Xmx2G: Set maximum heap size to 2GB
REM - Xms512M: Set initial heap size to 512MB
REM --enable-native-access=ALL-UNNAMED: Enable native access for all unnamed modules to allow loading native libraries (e.g. SQLite JDBC) without module system issues
set JAVA_OPTS=-Xmx2G -Xms512M --enable-native-access=ALL-UNNAMED


REM Run the server
java %JAVA_OPTS% -cp "lib/*" eu.torvian.chatbot.server.main.ServerMain

