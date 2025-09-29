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