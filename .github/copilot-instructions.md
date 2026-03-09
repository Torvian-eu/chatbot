# Project Information

## Tech stack
- Kotlin 2.3.10
- Compose Multiplatform 1.10.2 with Material 3 1.9.0
- Ktor 3.4.1 (Server & HTTP Client)
- Exposed 1.1.1
- SQLite JDBC 3.51.2.0
- Log4j 2.25.3
- HikariCP 7.0.2
- Koin 4.1.1
- Arrow 2.2.2
- SQLDelight 2.2.1 (client app only)
- Gradle 9.4.0

## Guidelines
- Error return types in Arrow should be logical errors, not technical errors.

## Running Gradle tasks
- `./gradlew app:desktopMainClasses` to build the desktop application
- `./gradlew app:desktopTestClasses` to build the desktop tests
- `./gradlew app:desktopTest` to run the desktop tests
- `./gradlew server:assemble` to build the server module
- `./gradlew server:test` to run the server tests

Note: Don't run `./gradlew build` as it takes too long.

## Tips
- Console commands are run in PowerShell.
- In Powershell several commands can be chained together with the character `;`. (Don't use `&&` for this, which only works in Windows Command prompt).
- There's no need to `cd` into the project directory before running Gradle tasks. The current working directory is already the project root.