@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions EnableDelayedExpansion

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem ==========================================================
@rem Optional CHATBOT-specific Gradle cache redirection
@rem
@rem Precedence:
@rem 1. CHATBOT_GRADLE_ROOT environment variable
@rem 2. local.gradle.properties in the project root
@rem 3. Otherwise: default Gradle behavior
@rem
@rem If a Gradle root is configured, add:
@rem   --no-watch-fs
@rem   --project-cache-dir <root>\main\project-cache
@rem ==========================================================

set "CHATBOT_GRADLE_ROOT_RESOLVED=%CHATBOT_GRADLE_ROOT%"

if not defined CHATBOT_GRADLE_ROOT_RESOLVED (
    if exist "%APP_HOME%\local.gradle.properties" (
        for /f "usebackq tokens=1,* delims==" %%A in ("%APP_HOME%\local.gradle.properties") do (
            set "KEY=%%A"
            set "VALUE=%%B"
            if "!KEY!"=="chatbot.gradle.root" (
                set "CHATBOT_GRADLE_ROOT_RESOLVED=!VALUE!"
            )
        )
    )
)

set "USE_CHATBOT_CACHE="

if defined CHATBOT_GRADLE_ROOT_RESOLVED (
    @rem Trim one leading space if present after '='
    if "!CHATBOT_GRADLE_ROOT_RESOLVED:~0,1!"==" " (
        set "CHATBOT_GRADLE_ROOT_RESOLVED=!CHATBOT_GRADLE_ROOT_RESOLVED:~1!"
    )

    @rem Resolve relative paths against the project root.
    if not "!CHATBOT_GRADLE_ROOT_RESOLVED:~1,1!"==":" (
        if not "!CHATBOT_GRADLE_ROOT_RESOLVED:~0,2!"=="\\" (
            set "CHATBOT_GRADLE_ROOT_RESOLVED=%APP_HOME%\!CHATBOT_GRADLE_ROOT_RESOLVED!"
        )
    )

    @rem Normalize to a clean absolute path
    for %%I in ("!CHATBOT_GRADLE_ROOT_RESOLVED!") do set "CHATBOT_GRADLE_ROOT_RESOLVED=%%~fI"

    set "PROJECT_CACHE_DIR=!CHATBOT_GRADLE_ROOT_RESOLVED!\main\project-cache"

    echo Using CHATBOT Gradle root: !CHATBOT_GRADLE_ROOT_RESOLVED! 1>&2
    echo Using project cache dir:   !PROJECT_CACHE_DIR! 1>&2

    set "USE_CHATBOT_CACHE=1"
)

@rem ==========================================================
@rem Export variables out of the local scope before endlocal
@rem ==========================================================
set "FINAL_USE_CHATBOT_CACHE=%USE_CHATBOT_CACHE%"
set "FINAL_PROJECT_CACHE_DIR=%PROJECT_CACHE_DIR%"
set "FINAL_JAVA_EXE=%JAVA_EXE%"
set "FINAL_APP_HOME=%APP_HOME%"
set "FINAL_APP_BASE_NAME=%APP_BASE_NAME%"
set "FINAL_DEFAULT_JVM_OPTS=%DEFAULT_JVM_OPTS%"
set "FINAL_JAVA_OPTS=%JAVA_OPTS%"
set "FINAL_GRADLE_OPTS=%GRADLE_OPTS%"

endlocal & (
    set "FINAL_USE_CHATBOT_CACHE=%FINAL_USE_CHATBOT_CACHE%"
    set "FINAL_PROJECT_CACHE_DIR=%FINAL_PROJECT_CACHE_DIR%"
    set "FINAL_JAVA_EXE=%FINAL_JAVA_EXE%"
    set "FINAL_APP_HOME=%FINAL_APP_HOME%"
    set "FINAL_APP_BASE_NAME=%FINAL_APP_BASE_NAME%"
    set "FINAL_DEFAULT_JVM_OPTS=%FINAL_DEFAULT_JVM_OPTS%"
    set "FINAL_JAVA_OPTS=%FINAL_JAVA_OPTS%"
    set "FINAL_GRADLE_OPTS=%FINAL_GRADLE_OPTS%"
)

if defined FINAL_USE_CHATBOT_CACHE (
    "%FINAL_JAVA_EXE%" %FINAL_DEFAULT_JVM_OPTS% %FINAL_JAVA_OPTS% %FINAL_GRADLE_OPTS% "-Dorg.gradle.appname=%FINAL_APP_BASE_NAME%" -jar "%FINAL_APP_HOME%\gradle\wrapper\gradle-wrapper.jar" --no-watch-fs --project-cache-dir "%FINAL_PROJECT_CACHE_DIR%" %*
) else (
    "%FINAL_JAVA_EXE%" %FINAL_DEFAULT_JVM_OPTS% %FINAL_JAVA_OPTS% %FINAL_GRADLE_OPTS% "-Dorg.gradle.appname=%FINAL_APP_BASE_NAME%" -jar "%FINAL_APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*
)

call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
