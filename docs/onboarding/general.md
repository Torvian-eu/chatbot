# Project Onboarding: Torvian Chatbot Application

## Project Purpose
Torvian Chatbot is a self-hosted AI workspace for users who want control over data, model providers, and tool execution. You run the server, bring your own LLM setup, and keep humans in the loop for agentic actions.

### Key Features
- **LLM Providers**: Bring your own OpenAI-compatible APIs and local Ollama models.
- **Real-time Streaming**: Stream model responses in real time, with support for concurrent responses from different chat sessions.
- **Threaded Conversations**: Organize long conversations with threaded/branched message flows.
- **Tool Execution Control**: Tool calls require approval by default, with optional per-tool auto-approve or auto-deny preferences.
- **Conversation Restart**: Easily restart agentic conversations from any previous message, with full context and tool state restoration.
- **Multi-user Access**: Manage multi-user access with authentication plus role/permission-based authorization.
- **Multi-platform Clients**: Desktop, Web (WASM), and Android clients, all connecting to the same server.
- **Always-on Tools**: Run tools 24/7 on any machine (local, remote, VM), independent from where the client apps are running.

### Platform Architecture
- **Server Module**: Core API and orchestration layer for authentication/authorization, chat sessions, message processing, LLM integration, tool lifecycle, and persistence.
- **Desktop Client**: Compose Multiplatform desktop application (Windows, macOS, Linux)
- **Web Client**: WASM-based web application
- **Android Client**: Android application
- **Worker (optional)**: Standalone process for MCP tool execution, useful for workspace isolation and always-on availability.
- **Common Module**: Shared business logic and models

### Project Status
- **Server + Desktop**: Most complete and stable combination for daily use.
- **Web (WASM)**: Stable with some limitations.
- **Android**: Early-stage usability with known UX limitations.

## Tech Stack
- **Languages**: Kotlin 2.3.21
- **UI Framework**: Compose Multiplatform 1.11.0 (Material 3 1.9.0)
- **Server**: Ktor 3.5.0
- **Database**: SQLite JDBC 3.53.1.0 with Exposed ORM 1.3.0
- **Dependency Injection**: Koin 4.2.1
- **Functional Programming**: Arrow 2.2.2.1
- **Logic**: kotlinx.serialization 1.11.0
- **Build Tool**: Gradle 9.4.0
- **MCP**: MCP Kotlin SDK 0.12.0
- **Migration**: Flyway 12.6.2
- **Testing**: MockK 1.14.9

## Codebase Structure
The project is organized into four main modules (`app/`, `server/`, `worker/`, `common/`) plus build infrastructure.

### Module Overview
- `app/`: Desktop client (Compose Multiplatform) for Windows, macOS, Linux, Web (WASM), and Android.
- `server/`: Ktor-based backend with SQLite database, REST API, and WebSocket support.
- `worker/`: Standalone process for MCP tool execution with WebSocket communication to server.
- `common/`: Shared DTOs, domain models, security utilities, and API resource definitions.
- `build-logic/`: Gradle convention plugins for consistent build configuration.
- `docs/`: Documentation including architecture flows, user guides, and project directory tree.

### App Module (`app/src/`)
The client application uses Compose Multiplatform with platform-specific implementations.

| Path | Description |
|------|-------------|
| `commonMain/kotlin/eu/torvian/chatbot/app/compose/` | Shared Compose UI components (screens, dialogs, components) |
| `commonMain/kotlin/eu/torvian/chatbot/app/domain/` | Domain contracts, events, and navigation |
| `commonMain/kotlin/eu/torvian/chatbot/app/repository/` | Repository interfaces and implementations |
| `commonMain/kotlin/eu/torvian/chatbot/app/service/` | API clients (Ktor), authentication, MCP management |
| `commonMain/kotlin/eu/torvian/chatbot/app/viewmodel/` | ViewModels for all screens (auth, chat, settings, admin) |
| `commonMain/kotlin/eu/torvian/chatbot/app/config/` | Client configuration and config loader |
| `commonMain/kotlin/eu/torvian/chatbot/app/koin/` | Koin dependency injection modules |
| `desktopMain/kotlin/` | Desktop-specific implementations (main entry, platform services) |
| `wasmJsMain/kotlin/` | Web/WASM-specific implementations |
| `androidMain/kotlin/` | Android-specific implementations |

### Server Module (`server/src/main/kotlin/eu/torvian/chatbot/server/`)
The backend uses Ktor with Exposed ORM for SQLite persistence.

| Path | Description |
|------|-------------|
| `config/` | Configuration loading, DTOs, and SSL validation |
| `data/dao/` | Data Access Object interfaces and Exposed implementations |
| `data/entities/` | Database entity mappings |
| `data/tables/` | Exposed table definitions with mappers |
| `domain/config/` | Domain configuration models (CORS, database, network, SSL) |
| `domain/security/` | Security domain models (JWT, user context) |
| `ktor/routes/` | Ktor route definitions (REST endpoints) |
| `ktor/auth/` | Authentication utilities |
| `service/core/` | Core business services (Chat, Session, Message, User, Group, etc.) |
| `service/core/impl/` | Service implementations |
| `service/core/error/` | Service error types (Arrow Either) |
| `service/llm/` | LLM API integration (OpenAI, Ollama, OpenRouter strategies) |
| `service/llm/discovery/` | Model discovery strategies |
| `service/llm/strategy/` | Chat completion strategies |
| `service/security/` | Authentication, authorization, certificate management |
| `service/tool/` | Built-in tool executors (weather, web search) |
| `service/mcp/` | Local MCP executor for tool execution |
| `service/setup/` | Initial data setup and initialization |
| `worker/command/` | Worker command dispatch and pending command registry |
| `worker/mcp/` | MCP runtime control, config sync, tool call dispatch |
| `worker/protocol/` | WebSocket protocol handling |
| `worker/session/` | Connected worker session management |

### Worker Module (`worker/src/main/kotlin/eu/torvian/chatbot/worker/`)
The worker process handles MCP tool execution, running separately from the server.

| Path | Description |
|------|-------------|
| `auth/` | Worker authentication (challenge signing, token store) |
| `config/` | Worker configuration loading |
| `mcp/` | MCP client service, process management, tool execution |
| `protocol/` | Custom JSON protocol for server communication |
| `protocol/handshake/` | WebSocket handshake handling |
| `protocol/interaction/` | Interaction abstractions for commands and tool calls |
| `protocol/routing/` | Message routing |
| `protocol/transport/` | WebSocket transport layer |
| `runtime/` | Worker runtime lifecycle |
| `setup/` | Worker setup (certificate, credentials, SSL) |

### Common Module (`common/src/commonMain/kotlin/eu/torvian/chatbot/common/`)
Shared code used by both server and app modules.

| Path | Description |
|------|-------------|
| `api/` | API resource definitions (Ktor Resources plugin) |
| `api/resources/` | Type-safe route resources |
| `models/api/` | Request/response DTOs organized by domain |
| `models/core/` | Domain models (ChatSession, ChatMessage, ChatGroup) |
| `models/llm/` | LLM models (LLMProvider, LLMModel, ModelSettings) |
| `models/tool/` | Tool models (ToolDefinition, ToolCall, ToolCallStatus) |
| `models/user/` | User models (User, Role, Permission, UserGroup) |
| `models/worker/` | Worker DTOs |
| `security/` | Encryption, password validation, JWT utilities |
| `misc/di/` | Dependency injection container abstraction |
| `misc/transaction/` | Transaction scope abstractions |

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

### Overview
The server provides a RESTful API built with Ktor using the Resources plugin for type-safe routing. It also supports WebSocket connections for real-time chat streaming and worker communication.

| Aspect | Details |
|--------|---------|
| Base URL | `https://yourdomain.com/api/v1` |
| WebSocket | `wss://yourdomain.com/api/v1/ws` |
| Authentication | JWT Bearer tokens (access & refresh) |
| Protocol | HTTP/1.1 + WebSocket |

### Key API Resources
- `/api/v1/auth` - Authentication (login, register, refresh, change-password)
- `/api/v1/sessions` - Chat sessions (create, list, clone, stream messages)
- `/api/v1/messages` - Chat messages (get, delete, update content)
- `/api/v1/groups` - Chat groups (create, list, manage)
- `/api/v1/providers` - LLM Provider management (add, test, discover models)
- `/api/v1/models` - LLM Model management
- `/api/v1/settings` - Model settings
- `/api/v1/tools` - Tool definitions
- `/api/v1/mcp/servers` - MCP server management (create, start, stop)
- `/api/v1/mcp/tools` - MCP tool definitions
- `/api/v1/users` - User management (admin)
- `/api/v1/roles` - Role management (admin)
- `/api/v1/user-groups` - User group management (admin)
- `/api/v1/workers` - Worker registration and management

### Access Control
- Role-based and resource-based access control
- Users can only access resources they own or that are shared with their user group
- Admin endpoints require the `ADMIN` role

For a complete list of all API endpoints with HTTP methods, see [API Documentation](API.md).

## LLM Integration

### Supported Providers
- **OpenAI**: OpenAI-compatible APIs (GPT models)
- **Ollama**: Local Ollama models
- **OpenRouter**: Multi-provider aggregation
- **Other OpenAI-compatible APIs**: Any API that follows the OpenAI specification

### Key Components
- `ChatCompletionStrategy` - Abstract base for provider-specific implementations
- `OpenAIChatStrategy` - OpenAI-compatible API implementation
- `OllamaChatStrategy` - Ollama API implementation
- `ModelDiscoveryStrategy` - Discovers available models from providers

### Model Discovery
The server can automatically discover available models from each provider using their respective endpoints (`/v1/models` for OpenAI, `/api/tags` for Ollama).

## Security

### Authentication
- **JWT Bearer tokens** for API authentication (access & refresh tokens)
- Token refresh mechanism for seamless session management

### Data Protection
- **AES encryption** for stored credentials (API keys, etc.)
- **BCrypt** for password hashing

### Transport Security
- **SSL/TLS** support for all connections
- Certificate management for server and worker communication

### Authorization
- Role-based access control (RBAC)
- Resource-based ownership model (users own their sessions, messages, providers, models, settings)
- User groups for sharing resources between users

## Database

### Technology
- **SQLite** as the database engine
- **Exposed ORM** for type-safe database operations
- **Flyway** for database migrations

### Key Tables
- Users, Roles, Permissions
- ChatSessions, ChatMessages
- LLMProviders, LLMModels, ModelSettings
- ToolDefinitions, ToolCalls
- LocalMCPServers, LocalMCPToolDefinitions
- Workers, UserGroups

### Ownership Model
Every resource has an owner (user). Resources can be shared with user groups. Access control ensures users can only access their own or shared resources.

## Configuration

### Configuration Files
- `application.json` - Main configuration (server settings, CORS, etc.)
- `secrets.json` - Sensitive data (API keys, database credentials)
- `setup.json` - Initial setup data (admin account, default roles)

### Environment Variables
All configuration values can be overridden via environment variables using the `env-mapping.json` file.

### Client Configuration
The desktop client has its own configuration system with platform-specific implementations for secure storage.

## Build & Run

### Build Commands
```bash
# Build server
./gradlew server:installDist

# Build desktop client
./gradlew app:createDistributable

# Build worker
./gradlew worker:installDist

# Build web client
./gradlew app:wasmJsBrowserDistribution
```

### Running the Application
- **Server**: `server/build/install/server/bin/server` (or `.bat` on Windows)
- **Desktop Client**: `app/build/compose/binaries/main/app/Chatbot`
- **Worker**: `worker/build/install/worker/bin/worker` (or `.bat` on Windows)

For detailed build and run instructions, see [README.md](../../README.md).

## Testing

### Framework
- **MockK** for mocking in Kotlin
- **Kotest** for assertions (in some modules)

### Test Structure
- Unit tests alongside source code in `src/test/kotlin/`
- Integration tests for API routes
- Service implementation tests

### Running Tests
```bash
# Server tests
./gradlew server:test

# Desktop tests
./gradlew app:desktopTest
```

## Deployment

### Options
1. **Direct Execution**: Run server, worker, and client directly using Java
2. **Docker**: Use pre-built Docker images from ghcr.io
3. **Docker Compose**: Full stack deployment with Caddy reverse proxy

### Docker Quick Start
```bash
# Server
docker run -d --name chatbot-server -p 8080:8080 \
  -e SERVER_HOST=0.0.0.0 ghcr.io/torvian-eu/chatbot-server:latest

# Worker
docker run -it --name chatbot-worker \
  -e CHATBOT_WORKER_SETUP_SERVER_URL=http://host.docker.internal:8080 \
  ghcr.io/torvian-eu/chatbot-worker:latest
```

For detailed deployment instructions, see [deploy/README.md](../../deploy/README.md).
