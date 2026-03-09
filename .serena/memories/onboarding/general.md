# Project Onboarding: Chatbot Application

## Project Purpose
The project is a multi-platform chatbot application with AI/LLM integration. It features a central server and multiple client options (Desktop, Web via WASM). It supports various LLM providers (OpenAI, Ollama) and has a plugin system for tools (Weather, Web Search, MCP).

- **Server Module**: Ktor-based backend with SQLite database, user authentication, and LLM provider integration
- **Desktop Client**: Compose Multiplatform desktop application (Windows, macOS, Linux)
- **Web Client**: WASM-based web application (planned)
- **Common Module**: Shared business logic and models

## Tech Stack
- **Languages**: Kotlin 2.3.10
- **UI Framework**: Compose Multiplatform 1.10.2 (Material 3 1.9.0)
- **Server**: Ktor 3.4.1
- **Database**: SQLite with Exposed ORM 1.1.1 (Server) and SQLDelight 2.2.1 (App)
- **Dependency Injection**: Koin 4.1.1
- **Functional Programming**: Arrow 2.2.2
- **Logic**: kotlinx.serialization
- **Build Tool**: Gradle 9.4.0

## Codebase Structure
- `app/`: Desktop client (Compose Multiplatform).
- `server/`: Ktor-based backend services and API.
- `common/`: Shared DTOs, domain models, and utilities.
- `build-logic/`: Gradle convention plugins.
- `docs/`: Extensive documentation including OpenAPI specs and architecture details.

## Style and Conventions
- **Naming**: Standard Kotlin naming conventions.
- **Error Handling**: Uses Arrow for logical errors (e.g., `Either`). Technical errors are handled via exceptions.
- **UI**: Follow Material 3 design guidelines.
- **Transactions**: Abstracted via `TransactionScope` and platform-specific implementations.

## Design Patterns
- **Layered Architecture**: Separation of presentation, business, and data layers.
- **Repository Pattern**: Used in the app module to abstract data sources.
- **DAO Pattern**: Used in the server module with Exposed.
- **Strategy Pattern**: Used for LLM provider implementations (`ChatCompletionStrategy`).
- **Dependency Injection**: Koin is used throughout.
- **Client-Server**: Default architecture is decoupled, though monolith support is planned.

## API Structure

### REST API Endpoints
- Base URL: `https://localhost:8443/api/v1`
- Authentication: JWT Bearer tokens
- Uses Ktor Resources plugin for type-safe routing

### Key API Resources
- `/api/v1/auth` - Authentication (login, register, refresh)
- `/api/v1/providers` - LLM Provider management
- `/api/v1/models` - LLM Model management
- `/api/v1/sessions` - Chat sessions
- `/api/v1/messages` - Chat messages
- `/api/v1/groups` - Chat groups
- `/api/v1/settings` - User settings
- `/api/v1/users` - User management (admin)
- `/api/v1/roles` - Role management (admin)
- `/api/v1/user-groups` - User group management (admin)
- `/api/v1/tools` - Tool definitions
- `/api/v1/mcp/servers` - MCP server management
- `/api/v1/mcp/tools` - MCP tool management

## LLM Integration

### LLM Providers Supported
- **OpenAI**: Uses `/v1/models` endpoint
- **Ollama**: Uses `/api/tags` endpoint
- **Anthropic**: (planned)
- Other OpenAI-compatible APIs

### Key Classes
- `LLMApiClient` - Interface for LLM API interactions
- `LLMApiClientKtor` - Ktor implementation
- `ChatCompletionStrategy` - Abstract base for provider-specific strategies
- `OpenAIChatStrategy` - OpenAI-compatible API implementation
- `OllamaChatStrategy` - Ollama API implementation
- `LLMProviderService` - Server-side provider management
- `LLMModelService` - Server-side model management

## Common Module

### Shared Models Location
- `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/`
- `api/` - API request/response DTOs
- `core/` - Domain models (ChatSession, ChatMessage, ChatGroup)
- `llm/` - LLM-related models (LLMProvider, LLMModel)
- `tool/` - Tool-related models
- `user/` - User-related models (User, Role, Permission)

### Security
- `common/security/` - Encryption utilities, password validation
- Uses AES encryption for stored credentials
- JWT for authentication

## App Module Structure

### UI Layer
- `app/src/commonMain/kotlin/eu/torvian/chatbot/app/compose/` - Compose UI components
- ViewModels in `viewmodel/` folder
- Domain contracts in `domain/contracts/`

### Data Layer
- `repository/` - Repository interfaces and implementations
- `service/api/` - API client interfaces
- `service/api/ktor/` - Ktor HTTP client implementations

### Local Database
- Uses SQLDelight for local storage
- `database/dao/` - Local DAO interfaces and implementations
- Platform-specific implementations in `desktopMain/`, `androidMain/`, `wasmJsMain/`

## Server Module Structure

### Data Layer
- `server/src/main/kotlin/eu/torvian/chatbot/server/data/`
- `dao/` - Data Access Object interfaces
- `dao/exposed/` - Exposed ORM implementations
- `entities/` - Database entity mappings
- `tables/` - Exposed table definitions

### Service Layer
- `server/src/main/kotlin/eu/torvian/chatbot/server/service/`
- `core/` - Core business services (ChatService, SessionService, ProviderService, etc.)
- `llm/` - LLM API integration
- `security/` - Authentication and authorization
- `setup/` - Initial data setup
- `tool/` - Tool execution

### Routes
- `ktor/routes/` - Ktor route definitions
- Uses type-safe Resources plugin