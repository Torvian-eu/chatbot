# Project Information

## Tech stack
- Kotlin 2.2.0
- Compose 1.8.2 with Material 3
- Ktor 3.2.3 (Server & HTTP Client)
- Exposed 0.61.0
- Log4j 2.25.1
- Koin 4.1.0
- Arrow 2.1.2
- kotlinx.datetime
- SQLite (local persistence)
- Gradle

## Guidelines
- Use kotlinx.datetime instead of java.time.

## Tips
- JUnit 5 parallel testing can make console output hard to follow. For failed tests, run them individually to view output in order.
- By default, your console commands will be run in PowerShell.

## Running Gradle tasks
- `./gradlew assemble` to build all modules
- `./gradlew app:desktopMainClasses` to build the desktop application
- `./gradlew app:desktopTestClasses` to build the desktop tests
- `./gradlew app:desktopTest` to run the desktop tests
- `./gradlew server:assemble` to build the server module
- `./gradlew server:test` to run the server tests
