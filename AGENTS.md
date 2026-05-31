# AGENTS.md

## Project
Torvian chatbot is a multiplatform chatbot application with AI/LLM integration. It has a central server, multiple clients (Desktop, Web, Android), support for providers such as OpenAI and Ollama, and a plugin system for MCP tools.

## Structure
- `server/` — server module
- `worker/` — MCP tool execution worker
- `app/` — client application module
- `common/` — shared code
- `build-logic/` — Gradle convention plugins
- `docs/` — documentation

## Stack
- Kotlin 2.3.21
- Compose Multiplatform 1.11.0 with Material 3 1.9.0
- Ktor 3.5.0
- Exposed 1.3.0
- SQLite JDBC 3.53.1.0
- Koin 4.2.1
- Arrow 2.2.2.1
- Kotlinx Serialization 1.11.0
- MCP Kotlin SDK 0.12.0
- Flyway 12.6.2
- MockK 1.14.9
- Gradle 9.4.0

## Conventions
- Error return types in Arrow should be logical errors, not technical errors.
- For modern Arrow typed-error style, follow [`docs/Misc/arrow-typed-errors.md`](docs/Misc/arrow-typed-errors.md)
- Write complete KDoc for every added or modified Kotlin declaration, including `private` and `internal` classes, functions, constructors, and properties. KDoc must describe intent and semantics, not repeat names, and include all applicable tags such as `@param`, `@property`, `@return`, `@receiver`, and `@throws`. Exception: Don't add KDoc for `override` members, unless the implementation has specific behavior that differs from the contract.
- Add concise inline comments wherever logic, invariants, control flow, workarounds, or domain rules are not obvious from the code itself. Comments should explain why, not merely restate what the code does.

## Build and test
- `./gradlew app:desktopMainClasses`
- `./gradlew app:desktopTestClasses`
- `./gradlew app:desktopTest`
- `./gradlew server:assemble`
- `./gradlew server:test`

Do not run `./gradlew build`.