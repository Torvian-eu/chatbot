# AGENTS.md

## Project
Torvian chatbot is a multiplatform chatbot application with AI/LLM integration. It has a central server, multiple clients (Desktop, Web, Android), support for providers such as OpenAI and Ollama, and a plugin system for MCP tools.

## Structure
- `server/` — server module (JVM) with Ktor backend and Exposed ORM
- `worker/` — worker module (JVM) for MCP tool execution. Connects to the server via WebSocket and executes tool calls dispatched by the server.
- `app/` — client application module (KMP) with Compose Multiplatform UI and Ktor client
- `common/` — shared code module (KMP) with common models, API definitions, and utilities

### Server Module (`server/src/main/kotlin/eu/torvian/chatbot/server/`)
- `main/` — Ktor application entry point and setup
- `config/` — configuration loading and DTOs for server settings
- `koin/` — Koin modules for dependency injection
- `domain/` — domain models for configuration and security
- `data/` — database access layer with Exposed (DAO interfaces, entities, tables)
- `service/` — core business services (Chat, Session, Message, User, Group, etc.), LLM integration, security, tools, and MCP execution
- `ktor/` — Ktor route definitions
- `worker/` — worker command dispatch and pending command registry

### Worker Module (`worker/src/main/kotlin/eu/torvian/chatbot/worker/`)
- `auth/` — worker authentication (challenge signing, token store)
- `config/` — worker configuration loading
- `mcp/` — MCP client service, process management, tool execution
- `protocol/` — custom JSON protocol for server communication, including handshake, interaction abstractions, routing, and transport layers
- `runtime/` — worker runtime lifecycle management
- `setup/` — worker setup (certificate, credentials, SSL)

### App Module (`app/src/`)
- `commonMain/` — shared client code (UI components, domain contracts, repositories, API clients, ViewModels, config, Koin modules)
- `desktopMain/` — desktop-specific implementations (main entry, platform services)
- `wasmJsMain/` — web/WASM-specific implementations
- `androidMain/` — Android-specific implementations

### Common Module (`common/src/commonMain/kotlin/eu/torvian/chatbot/common/`)
- `api/` — API resource definitions (Ktor Resources plugin)
- `models/` — shared models for API requests/responses, core domain models, LLM models, tool models, user models, and worker DTOs
- `security/` — encryption, password validation, JWT utilities
- `misc/` — miscellaneous utilities such as dependency injection container abstraction and transaction scope abstractions

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
- `./gradlew server:classes`
- `./gradlew server:testClasses`
- `./gradlew server:test`
- (similarly for `worker` and `common` modules.)

Do not run `./gradlew build` as it is too heavy.
