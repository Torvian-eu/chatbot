# Local MCP Servers - Epic Breakdown

## Executive Summary

This document breaks down the **NF.EA1 - Local MCP Servers** epic into implementable user stories. The epic enables LLMs to call local tools via the Model Context Protocol (MCP), allowing access to real-time data and external actions.

**Total User Stories**: 25 (across 8 implementation phases)

**Key Architecture**: 
- **Client-Side Local Storage**: MCP server configurations stored locally using SQLDelight (Desktop, Android, WASM)
- **Server-Side Minimal Storage**: Server only generates unique IDs and tracks ownership (linkage support)
- **US2.2 (LocalMCPServerManager)** - High-level orchestration layer
- **US2.3 (MCPClientService)** - MCP-specific operations layer (no Repository/API dependencies)
- **US6.1A (ToolRepository Extensions)** - MCP tool persistence (extends existing ToolRepository)
- Clean separation: UI → Manager → (MCP Repo + Tool Repo) → MCP Operations → Process Management
- **Clean concerns**: LocalMCPServerRepository = MCP servers (local storage), ToolRepository = tools (server storage)

---

## Epic Definition

### NF.EA1 - Local MCP Servers (Epic)

**Description**: As a user, I want the LLM to be able to call local tools via the Model Context Protocol (MCP), so that the LLM can access real-time data and perform actions in the external world (such as searching the web, accessing local files, controlling GitHub repos, etc.).

**Estimate**: XL (9-12 weeks)

**Acceptance Criteria**:

#### MCP Server Configuration
- [ ] The user can configure their own local (STDIO) MCP servers with:
  - A name for the server (unique per user)
  - An executable command to launch the server (e.g., "java", "uv", "docker")
  - Arguments to pass to the command (as array)
  - Environment variables to set before launching the server (e.g., GitHub access token)
    - **Encrypted client-side** with user-provided key for security
  - A working directory to launch the server in (optional)
  - Auto-start configuration:
    - `autoStartOnEnable` - start when tool is enabled for session
    - `autoStartOnLaunch` - start when application launches
    - `autoStopAfterInactivitySeconds` - auto-stop after inactivity
  - Default tool enablement for new sessions:
    - `toolsEnabledByDefault` - whether tools from this server are enabled by default

#### MCP Server Management
- [ ] The user can test the connection to an MCP server
  - Tests connection by listing available tools
  - Shows success/failure status with tool count
  - Client-side operation (no server API needed)
- [ ] The user can discover tools from an MCP server
  - Lists all available tools via MCP SDK
  - Persists tools to database (ToolDefinitionTable + LocalMCPToolDefinitionTable)
  - Server creates both entries atomically (single transaction)
- [ ] The user can refresh the list of tools from an MCP server
  - Compares current tools with existing tools
  - Adds new tools, updates changed tools, removes deleted tools
  - Preserves user's per-session tool enablement settings
- [ ] MCP servers can be started/stopped from within the application
  - Process lifecycle managed by LocalMCPServerProcessManager (client-side)
  - Process status tracking (running/stopped/error)
  - Graceful shutdown and cleanup

#### Data Persistence
- [ ] MCP server configurations are stored **locally on the client machine** (using SQLDelight)
  - Each platform (Desktop, Android, WASM) has its own independent storage
  - Configurations are **NOT synchronized** between platforms (different commands/paths per platform)
  - Environment variables are encrypted client-side using CryptoProvider
  - WASM platform: Local storage only (no MCP server execution support)
- [ ] Server-side storage for MCP servers (minimal):
  - Stores only `id` and `userId` in LocalMCPServerTable (for linkage purposes)
  - Server generates unique ID when client creates an MCP server configuration
  - Client stores this ID along with full configuration locally
  - Enables consistent tool-to-server linkage across client and server
- [ ] MCP tools are stored in the tool definitions table (ToolDefinitionTable) **on the server**
  - Type: `ToolType.MCP_LOCAL`
  - Linked to MCP server via LocalMCPToolDefinitionTable (junction table)
  - **Tool names are NOT globally unique** (only unique within session's enabled tools)
  - Support for optional name mapping via `mcpToolName` field (future feature)

#### Session-Level Tool Configuration
- [ ] The user can enable/disable MCP servers for specific chat sessions
  - Per-session configuration via SessionToolConfigTable
  - Only globally enabled tools can be enabled for sessions
- [ ] The user can select which tools from each MCP server are enabled for the current chat session
  - Individual tool enable/disable per session
  - Tools must be both globally enabled AND session-enabled to be available
- [ ] The user can configure default tool enablement for new sessions
  - Server-level default: `LocalMCPServerTable.toolsEnabledByDefault`
  - Tool-level override: `ToolDefinitionTable.isEnabledByDefault`
  - Hierarchy: Tool override → Server default → Disabled

#### UI & User Experience
- [ ] The user can view and manage MCP servers from the UI
  - List of configured servers with status (running/stopped/error)
  - Add/Edit/Delete server operations
  - Test connection and discover/refresh tools buttons
  - Enable/Disable server toggle
- [ ] The user can view the list of available tools from each MCP server
  - Tool count displayed per server
  - Tool details (name, description, schema)
  - Enable/disable tools for current session

#### LLM Integration & Tool Execution
- [ ] The LLM can request to call tools exposed by local MCP servers
  - LocalMCPToolExecutor handles MCP tool execution requests
  - Registered in ToolExecutorFactory for `ToolType.MCP_LOCAL`
- [ ] Tool execution uses WebSocket for bidirectional communication
  - **Server-to-Client**: Tool execution requests via `ChatEvent.MCPToolExecutionRequested`
  - **Client-to-Server**: Tool results via `ToolResult` objects
  - WebSocket replaces SSE on `POST /api/v1/sessions/{sessionId}/messages` route
  - Single WebSocket connection per message session handles both streaming and tool execution
- [ ] The client executes MCP tools locally on behalf of the LLM
  - LocalMCPToolExecutionHandler receives execution requests via WebSocket
  - Calls LocalMCPClientWrapper to execute tool on local MCP server
  - Sends results back to server via WebSocket
- [ ] The server receives tool results and forwards them to the LLM
  - ChatService collects tool results from `Flow<ToolResult>` parameter
  - Includes results in next LLM request
  - Supports multi-turn tool calling loops

#### Architecture & Technical Details
- [ ] MCP clients are managed by the desktop application
  - LocalMCPServerProcessManager launches MCP server processes (STDIO)
  - MCPClientService wraps MCP SDK Client for communication
  - LocalMCPServerManager orchestrates high-level workflows
- [ ] Clean separation of concerns by entity type:
  - LocalMCPServerRepository manages MCP server data
  - ToolRepository manages tool data (all types including MCP)
  - No cross-repository dependencies
- [ ] Process lifecycle management
  - Auto-start modes: On Demand (default), On Enable, On Launch
  - Auto-stop after configurable inactivity
  - Graceful shutdown and cleanup
- [ ] Health monitoring and logging
  - Server health status tracking
  - Tool execution logs (in-memory, not persisted)
  - Error notifications for failures

**Out of Scope** (Future Implementation):
- Remote MCP servers (HTTP/SSE transport) - enum value reserved: `ToolType.MCP_REMOTE`
- MCP server configuration sharing between users (blocked by encryption)
- Group-based access control for MCP servers
- Tool name mapping feature (schema field added but not implemented)
- Containerized/sandboxed MCP server execution

---

### Key Design Decisions

1. **Local-First Architecture**: This implementation focuses on **local (STDIO) MCP servers** running on the user's machine
   - All entities renamed with "Local" prefix: `LocalMCPServerTable`, `LocalMCPToolDefinitionTable`, etc.
   - `ToolType.MCP_LOCAL` enum value for local MCP tools
   - Optional `ToolType.MCP_REMOTE` for future remote HTTP-based MCP servers

2. **Client-Side Storage for MCP Server Configurations**:
   - **LocalMCPServer configurations stored locally** on each client platform (Desktop, Android, WASM)
   - Uses **SQLDelight** for KMP-compatible local database storage
   - Each platform has independent storage (no synchronization between platforms)
   - **Rationale**: 
     - Commands/paths differ per platform (e.g., Desktop vs Android vs WASM)
     - WASM cannot run local MCP servers (only stores configs, no execution)
     - Simplifies encryption/decryption (uses CryptoProvider from common module)
   - **Server-side minimal storage**: Server stores only `id` and `userId` in LocalMCPServerTable
     - Client requests ID generation from server when creating new MCP server config
     - Client stores full config locally along with server-generated ID
     - Enables consistent tool-to-server linkage across client and server databases

3. **Tool Name Uniqueness**: Tool names are **NOT globally unique**
   - Different users can have tools with the same name
   - Uniqueness only required within enabled tools for a given chat session
   - Application-level validation warns users of duplicate names

4. **Security & Privacy**:
   - Environment variables encrypted with CryptoProvider (may contain API keys/tokens)
   - **Reusable encrypted storage**: Separate EncryptedSecretTable for storing encrypted data
     - Envelope encryption (DEK encrypted with KEK)
     - Other tables reference encrypted secrets via foreign key
     - Enables consistent encryption approach across different sensitive fields
     - Supports future key rotation without immediate re-encryption
   - Local storage on client eliminates need for server-side decryption
   - Each platform manages its own encryption keys

5. **Future Features Marked**:
   - Group-based sharing not applicable (configs are local per platform)
   - Access control DAOs removed (not needed for local storage)

6. **Auto-Start Modes**:
   - **On Demand**: Start when LLM requests tool call (default behavior)
   - **On Enable**: Start when tool enabled for session (`autoStartOnEnable` field)
   - **On Launch**: Start when application launches (`autoStartOnLaunch` field)
   - Auto-stop after configurable inactivity period

7. **Two-Level Default Enablement for New Chat Sessions**:
   - Controls whether tools are **automatically enabled** when creating a new chat session
   - Server-level: `LocalMCPServer.toolsEnabledByDefault` (bulk configuration)
   - Tool-level: `ToolDefinition.isEnabledByDefault` (fine-grained override)
   - Hierarchy: Tool override → Server default → Disabled
   - **Note**: This is separate from global enable/disable:
     - `LocalMCPServer.isEnabled` - globally enable/disable the entire server
     - `ToolDefinition.isEnabled` - globally enable/disable a specific tool
     - If globally disabled, tools cannot be enabled for any session
     - If globally enabled, users can still manually enable/disable tools per session

8. **Client-Side Operations**:
   - Tool discovery, refresh, and connection testing are client-side operations
   - No server API endpoints for these operations (they happen on desktop client)

9. **WebSocket Integration for Tool Execution**:
   - Replace SSE with WebSocket on POST /api/v1/sessions/{sessionId}/messages route
   - Enables bidirectional communication for MCP tool execution
   - Tool execution requests sent via ChatEvent.MCPToolExecutionRequested
   - Tool results received via Flow<ToolResult> parameter in ChatService methods
   - Single WebSocket connection per message session handles both streaming and tool execution

## Architecture Overview

Based on the current codebase analysis, the implementation will follow these architectural patterns:

### Current Tool Architecture
- **Tool Definitions**: Stored in `ToolDefinitionTable` with metadata, schemas, and configuration
  - **Note**: Tool names are NOT globally unique - different users can have tools with the same name
  - Uniqueness is only required within enabled tools for a given chat session (enforced at application level)
- **Tool Types**: Enum-based (`ToolType.WEB_SEARCH`, `ToolType.CALCULATOR`, etc.)
- **Tool Execution**: Factory pattern with `ToolExecutor` interface and type-specific implementations
- **Session Configuration**: `SessionToolConfigTable` for per-session tool enablement
- **Tool Calls**: Tracked in `ToolCallTable` linked to assistant messages
- **Tool Enable/Disable Hierarchy**:
  1. **Global Enable/Disable**: Controls whether a tool CAN be used at all
     - `LocalMCPServer.isEnabled` - if false, ALL tools from this server are unavailable
     - `ToolDefinition.isEnabled` - if false, this specific tool is unavailable
  2. **Default Enablement for New Sessions**: Controls initial state when creating new chat sessions
     - `LocalMCPServerTable.toolsEnabledByDefault` - server-level default
     - `ToolDefinitionTable.isEnabledByDefault` - tool-level override
  3. **Per-Session Enablement**: User can manually enable/disable tools for each session
     - Stored in `SessionToolConfigTable`
     - Only possible if tool is globally enabled
     - Can be changed at any time during the session

### MCP Integration Points
- **MCP SDK**: Kotlin MCP SDK available for client-side tool discovery and execution
- **STDIO Transport**: `StdioClientTransport` for process communication
- **Tool Discovery**: `Client.listTools()` retrieves available tools from MCP servers
- **Tool Execution**: `Client.callTool()` invokes tools with arguments
- **Local Storage**: SQLDelight for KMP-compatible local database storage
  - Platform-independent schema definition
  - Type-safe generated DAO code
  - Supports Desktop, Android, and WASM platforms
- **Encrypted Secrets**: Reusable encrypted storage mechanism
  - Separate `EncryptedSecretTable` for storing encrypted data
  - Uses envelope encryption (EncryptionService + CryptoProvider)
  - Referenced via foreign keys from other tables
  - Enables consistent encryption across different sensitive fields
  - Supports key rotation via `keyVersion` tracking

### Ownership & Access Control
- **Ownership Tables**: One-to-one relationship (e.g., `MCPServerOwnersTable`)
- **Access Tables**: Group-based sharing (e.g., `MCPServerAccessTable`)
- **Authorization**: Route-level checks using `AuthorizationService`

### Communication Architecture
- **Server-Client**: WebSocket for bidirectional real-time communication
  - Replaces SSE on POST /api/v1/sessions/{sessionId}/messages route
  - Handles both message streaming (server → client) and tool execution (bidirectional)
- **Client-Server**: HTTP/REST for commands (CRUD operations on servers, tools, sessions)

### Client-Server Orchestration for MCP Servers

**Key Principle**: Server stores configuration, client executes MCP processes

#### Data Flow Overview
```
User creates MCP server config in UI
  ↓
LocalMCPServerRepository → API → Server (requests ID generation)
  ↓
Server generates unique ID → returns to client
  ↓
LocalMCPServerRepository stores full config locally (SQLDelight) with server ID
  ↓
LocalMCPServerRepository emits updated state
  ↓
UI shows new server (process not started yet)
  ↓
User clicks "Test Connection" or "Discover Tools"
  ↓
UI calls LocalMCPServerManager (high-level orchestration)
  ↓
LocalMCPServerManager loads config from LocalMCPServerRepository cache
  ↓
LocalMCPServerManager → MCPClientService (MCP operations)
  ↓
MCPClientService → LocalMCPServerProcessManager.startServer(config)
  ↓
Process launched → MCPClientService wraps MCP SDK Client
  ↓
Tools discovered → LocalMCPServerManager calls ToolRepository to persist
  ↓
ToolRepository → ToolApi → Server (stores tools + linkages atomically)
  ↓
ToolRepository invalidates cache (new tools created)
  ↓
UI shows discovered tools
```

#### Component Responsibilities

**Server-Side (Backend)**:
- Generate unique IDs for LocalMCPServer configurations
- Track ownership (userId to serverId mapping)
- Store ToolDefinition metadata and schemas (in database)
- Provide REST API for ID generation and tool CRUD operations
- Handle authorization and validation
- Track tool enablement per session
- Request tool execution via WebSocket events
- **Note**: Server does NOT store full MCP server configurations (only IDs)

**Client-Side (Desktop/Android/WASM App)**:
- **UI Layer**: ViewModels and Compose UI
- **Business Logic Layer**: LocalMCPServerManager (high-level orchestration)
  - Coordinates between Repository, MCPClientService, and API
  - Handles full user workflows (test, discover, refresh)
  - Manages data flow and persistence
- **MCP Operations Layer**: MCPClientService (MCP-specific operations - Desktop/Android only)
  - Manages MCP server processes (start/stop/status)
  - Wraps MCP SDK for tool discovery and execution
  - No knowledge of Repository or API
  - **Not available on WASM** (no process management)
- **Data Layer**: 
  - LocalMCPServerRepository (local SQLDelight storage + API for ID generation)
  - ToolRepository (server API for tool storage)
- **Network Layer**: API Client (server communication for IDs and tools)
- **Local Storage**: SQLDelight database for full MCP server configurations
  - Platform-specific storage (Desktop, Android, WASM)
  - Encrypted environment variables using CryptoProvider
  - Independent per platform (no synchronization)

#### Component Interaction Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENT SIDE                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌──────────┐                                                        │
│  │    UI    │  User clicks "Discover Tools"                         │
│  │ (Compose)│                                                        │
│  └────┬─────┘                                                        │
│       │                                                              │
│       ▼                                                              │
│  ┌──────────────┐                                                   │
│  │  ViewModel   │                                                   │
│  └──────┬───────┘                                                   │
│         │                                                            │
│         │ 1. Call discoverTools(serverId)                          │
│         ▼                                                            │
│  ┌──────────────────────────┐                                      │
│  │ LocalMCPServerManager    │ ◄──── NEW: High-level orchestration  │
│  │  (US2.3)                 │                                      │
│  └──┬───────┬───────────┬───┘                                      │
│     │       │           │                                            │
│     │       │           │ 2. Get config from cache                 │
│     │       │           ▼                                            │
│     │       │      ┌──────────────────────┐                        │
│     │       │      │LocalMCPServerRepo    │                        │
│     │       │      │  (US6.1)             │                        │
│     │       │      └──────────────────────┘                        │
│     │       │                                                        │
│     │       │ 3. Call MCP operations (pass config)                 │
│     │       ▼                                                        │
│     │  ┌──────────────────────────┐                                │
│     │  │  MCPClientService        │ ◄──── MCP-specific layer        │
│     │  │  (US2.4)                 │                                │
│     │  └──┬───────────┬───────────┘                                │
│     │     │           │                                              │
│     │     │           │ 4. Start process                           │
│     │     │           ▼                                              │
│     │     │      ┌──────────────────────────┐                      │
│     │     │      │LocalMCPServerProcess     │                      │
│     │     │      │Manager (US2.1)           │                      │
│     │     │      └──────────┬───────────────┘                      │
│     │     │                 │                                        │
│     │     │                 │ Launches OS process                  │
│     │     │                 ▼                                        │
│     │     │            ┌─────────────┐                             │
│     │     │            │ MCP Server  │ (external process)           │
│     │     │            │  Process    │                             │
│     │     │            └──────┬──────┘                             │
│     │     │                   │ STDIO                               │
│     │     │ 5. Connect & wrap SDK                                  │
│     │     ▼                   ▼                                      │
│     │  ┌──────────────────────────┐                                │
│     │  │  MCP SDK Client          │ ◄── Wraps official MCP SDK     │
│     │  │  (Kotlin SDK)            │                                │
│     │  └──────────────────────────┘                                │
│     │                                                                │
│     │ 6. Return discovered tools (MCP Tool objects)                │
│     │                                                                │
│     │ 7. Convert to ToolDefinition format                          │
│     │                                                                │
│     │ 8. Persist tools via ToolRepository                          │
│     ▼                                                                │
│  ┌──────────────────────┐                                          │
│  │  ToolRepository      │                                          │
│  │  (US6.1A)            │                                          │
│  └──┬───────────────────┘                                          │
│     │                                                                │
│     │ 9. Call ToolApi to persist tools + linkages                 │
│     ▼                                                                │
│  ┌──────────────────────┐                                          │
│  │     ToolApi          │                                          │
│  │  (US6.2)             │                                          │
│  └──────────┬───────────┘                                          │
│             │                                                        │
│             │ HTTP POST /api/v1/mcp-servers/{id}/tools/batch      │
│             ▼                                                        │
│  Server creates tools + linkages atomically                        │
│             │                                                        │
│             │ 10. Success response                                 │
│             ▼                                                        │
│  ┌──────────────────────┐                                          │
│  │  ToolRepository      │                                          │
│  │  (invalidate cache)  │                                          │
│  └──────────────────────┘                                          │
│                                                                      │
│  11. Return created tools to Manager                               │
│                                                                      │
└─────────────┼────────────────────────────────────────────────────────┘
              │ HTTP POST /api/v1/mcp-servers/{id}/tools/batch
              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          SERVER SIDE                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌──────────────────────┐                                          │
│  │  Ktor Route Handler  │                                          │
│  │  (US5.2)             │                                          │
│  └──────────┬───────────┘                                          │
│             │                                                        │
│             │ 9. Validate & authorize                              │
│             ▼                                                        │
│  ┌──────────────────────┐                                          │
│  │LocalMCPServerService │                                          │
│  │  (US1.4)             │                                          │
│  └──────────┬───────────┘                                          │
│             │                                                        │
│             │ 10. Create ToolDefinitions                           │
│             ▼                                                        │
│  ┌──────────────────────┐                                          │
│  │  ToolDefinitionDAO   │                                          │
│  │  (US1.2)             │                                          │
│  └──────────┬───────────┘                                          │
│             │                                                        │
│             │ 11. Insert to DB                                     │
│             ▼                                                        │
│  ┌─────────────────────────────────────┐                          │
│  │         Database (SQLite)            │                          │
│  │  - ToolDefinitionTable               │                          │
│  │  - LocalMCPToolDefinitionTable       │                          │
│  └─────────────────────────────────────┘                          │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

#### Key Classes and Data Flow

1. **LocalMCPServerRepository** (client-side):
   - Requests server ID generation from API when creating new MCP server
   - Stores full MCP server configuration **locally** using SQLDelight
   - Caches in `StateFlow<DataState<List<LocalMCPServer>>>`
   - Provides configs to ViewModels and Services
   - **Scope**: Only MCP server data (not tools)
   - `getToolsForServer()` delegates to ToolRepository
   - Does NOT orchestrate operations (that's LocalMCPServerManager)
   - **Storage**: Platform-specific SQLDelight database (Desktop, Android, WASM)
   - **Encryption**: Uses CryptoProvider for environment variables

2. **ToolRepository** (client-side - EXTENDED):
   - Manages all tool data (existing tools + MCP tools)
   - **New MCP-specific methods** (US6.1A):
     - `persistMCPTools(serverId, toolDefinitions)` - persist with linkages via API
     - `getToolsByServerId(serverId)` - filter tools by MCP server
     - `refreshMCPTools(serverId, toolDefinitions)` - update MCP server tools
   - Calls ToolApi for all tool operations
   - Invalidates cache when MCP tools change
   - No dependency on LocalMCPServerRepository

3. **LocalMCPServerManager** (client-side, NEW - US2.2):
   - High-level orchestration service
   - Handles complete user workflows (test, discover, refresh)
   - Coordinates between LocalMCPServerRepository, ToolRepository, and MCPClientService
   - Gets MCP server configs from LocalMCPServerRepository
   - Calls MCPClientService for MCP operations (passing config as parameter)
   - Calls ToolRepository to persist discovered tools
   - Returns results to ViewModels
   - **Dependencies**: LocalMCPServerRepository, ToolRepository, MCPClientService
   - **Clean separation**: MCP server data vs tool data
   - Returns results to ViewModels
   - **Dependencies**: LocalMCPServerRepository, MCPClientService, LocalMCPServerApi

3. **MCPClientService** (client-side, NEW - US2.4):
   - MCP-specific operations layer
   - Manages MCP server processes via LocalMCPServerProcessManager
   - Wraps MCP SDK Client for tool discovery and execution
   - Methods take LocalMCPServer config as parameter (not fetched internally)
   - No knowledge of Repository or API
   - Pure MCP operations: start/stop processes, discover tools, execute tools
   - **Dependencies**: LocalMCPServerProcessManager, MCP SDK Client
   - **Does NOT depend on**: Repository, API

4. **LocalMCPServerProcessManager** (client-side):
   - Pure process management (start/stop/status)
   - Receives LocalMCPServer config as parameter
   - No knowledge of Repository, API, or higher-level orchestration
   - Called by MCPClientService

5. **MCP SDK Client** (from Kotlin MCP SDK):
   - Official MCP SDK client
   - Wrapped by MCPClientService
   - Handles STDIO communication with MCP server processes
   - Provides tool discovery and execution

#### Example Flow: User Tests Connection

1. User clicks "Test Connection" button (UI)
2. ViewModel calls `localMCPServerManager.testConnection(serverId)`
3. LocalMCPServerManager gets config: `repository.servers.value.data.find { it.id == serverId }`
4. LocalMCPServerManager calls MCPClientService: `mcpClientService.startAndConnect(serverId, config)`
5. MCPClientService starts process: `processManager.startServer(config)`
6. MCPClientService wraps MCP SDK Client and connects via STDIO
7. MCPClientService lists tools: `mcpSdkClient.listTools()`
8. MCPClientService returns result (tool count or error)
9. LocalMCPServerManager returns result to ViewModel
10. UI shows success/failure

#### Example Flow: User Discovers Tools

1. User clicks "Discover Tools" button (UI)
2. ViewModel calls `localMCPServerManager.discoverTools(serverId)`
3. LocalMCPServerManager gets config from LocalMCPServerRepository cache
4. LocalMCPServerManager calls MCPClientService: `mcpClientService.discoverTools(serverId, config)`
5. MCPClientService starts process (if not running)
6. MCPClientService wraps MCP SDK Client and connects
7. MCPClientService lists tools from MCP server
8. MCPClientService returns List<MCP Tool> to LocalMCPServerManager
9. LocalMCPServerManager converts tools to ToolDefinition format
10. **LocalMCPServerManager calls ToolRepository** to persist tools:
   - `toolRepository.persistMCPTools(serverId, toolDefinitions)`
11. **ToolRepository calls ToolApi** to persist tools + linkages atomically:
   - `toolApi.createMCPToolsForServer(serverId, toolDefinitions)`
12. Server stores tools and linkages in DB (atomic transaction)
13. **ToolRepository invalidates its cache** (new tools created)
14. ToolRepository returns created tools to LocalMCPServerManager
15. LocalMCPServerManager returns discovered tools to ViewModel
16. UI shows discovered tools

---

#### Frequently Asked Questions

**Q: Where does the LocalMCPServer config parameter come from in `startServer(config: LocalMCPServer)`?**

A: The data flow is:
1. User creates MCP server via UI → Repository requests ID from server API → Server generates ID
2. Repository stores full config **locally** using SQLDelight (with server-generated ID)
3. Repository caches configs in StateFlow
4. LocalMCPServerManager reads from Repository cache: `repository.servers.value.data.find { it.id == serverId }`
5. LocalMCPServerManager passes config to MCPClientService: `mcpClientService.startAndConnect(serverId, config)`
6. MCPClientService passes config to ProcessManager: `processManager.startServer(config)`

**Q: Who calls `LocalMCPServerProcessManager.startServer()`?**

A: `MCPClientService` calls it (US2.3) when:
- LocalMCPServerManager (US2.2) requests tool discovery
- LocalMCPServerManager requests connection testing
- LocalMCPServerManager requests tool refresh
- Auto-start on enable (US7.2) - triggered by session tool config changes
- Auto-start on launch (US7.2) - triggered during app startup

**Q: Why have both LocalMCPServerManager AND MCPClientService?**

A: Separation of concerns:
- **LocalMCPServerManager** (US2.2): High-level orchestration
  - Knows about Repository, API, data transformation
  - Coordinates full workflows (test, discover, refresh)
  - Handles business logic
- **MCPClientService** (US2.3): MCP-specific operations
  - Pure MCP SDK interactions
  - No knowledge of Repository or API
  - Takes config as parameter
  - Reusable for any MCP operation
- **ProcessManager** (US2.1): Pure process management
  - No knowledge of MCP SDK, Repository, or API
  - Easiest to test in isolation

**Q: How do discovered tools get persisted?**

A: Client-side discovery, server-side persistence via ToolRepository:
1. LocalMCPServerManager calls MCPClientService.discoverTools(serverId, config)
2. MCPClientService starts process and wraps MCP SDK Client
3. MCPClientService calls mcpSdkClient.listTools() → returns List<MCP Tool>
4. LocalMCPServerManager converts MCP tools to ToolDefinition format
5. **LocalMCPServerManager calls ToolRepository**: `toolRepository.persistMCPTools(serverId, toolDefinitions)`
6. **ToolRepository calls ToolApi**: `toolApi.createMCPToolsForServer(serverId, toolDefinitions)`
7. **ToolApi calls**: `POST /api/v1/mcp-servers/{id}/tools/batch`
8. Server validates and stores in DB (ToolDefinitions + linkages) atomically
9. **ToolRepository invalidates its cache** (new tools created)
10. ToolRepository returns created tools to Manager
11. UI shows updated tool list

**Q: Why does ToolRepository handle MCP tool persistence instead of LocalMCPServerRepository?**

A: Separation of concerns by data type:
- **LocalMCPServerRepository**: Manages LocalMCPServer data (configs, settings)
- **ToolRepository**: Manages ALL tool data (regular tools + MCP tools)
- Tools are tools, regardless of source (built-in, custom, or MCP)
- ToolRepository already has caching, API patterns for tools
- Cleaner: Each repository manages one type of entity
- No cross-repository dependencies needed

**Q: What if MCP server process crashes?**

A: Error handling chain:
1. ProcessManager detects crash → returns error status
2. MCPClientService receives error → returns to LocalMCPServerManager
3. LocalMCPServerManager returns error to ViewModel
4. ViewModel updates UI state → shows error message
5. User can retry or check logs
6. Optional: Auto-restart (configurable in US7.2)

**Q: Can multiple parts of the app start the same MCP server simultaneously?**

A: MCPClientService should implement:
- Process status tracking (Map<serverId, ProcessState>)
- Idempotent start (if already running, return existing process)
- Reference counting (track who needs the process)
- Graceful shutdown only when ref count = 0

---

## User Stories

### Phase 1: Core MCP Server Management (Backend)

#### US1.0 - Encrypted Secret Storage Infrastructure (Client-Side)
**As a** developer
**I want** a reusable mechanism to store encrypted secrets in the database
**So that** sensitive data like environment variables can be stored securely and referenced from multiple tables

**Acceptance Criteria:**
- [ ] Create client-side SQLDelight schema `EncryptedSecretTable`:
  - `id` (Long, PK, auto-increment) - unique identifier for the encrypted secret
  - `encryptedSecret` (String, not null) - the secret encrypted with DEK, Base64 encoded
  - `encryptedDEK` (String, not null) - the DEK encrypted with KEK, Base64 encoded  
  - `keyVersion` (Int, not null) - version of KEK used for encryption
  - `createdAt` (Long, not null) - creation timestamp
  - `updatedAt` (Long, not null) - last update timestamp
- [ ] Create `EncryptedSecretLocalDao` interface (client-side) with methods:
  - `insert(encryptedSecret: EncryptedSecret)` - creates new encrypted secret, returns generated ID
  - `update(id: Long, encryptedSecret: EncryptedSecret)` - updates existing encrypted secret
  - `getById(id: Long)` - retrieves encrypted secret by ID
  - `deleteById(id: Long)` - deletes encrypted secret (with cascade handling)
  - `deleteUnreferenced()` - deletes orphaned secrets not referenced by any table
- [ ] Implement SQLDelight queries for CRUD operations
- [ ] Add reference counting mechanism (optional) to track usage
- [ ] Create helper service `EncryptedSecretService` (client-side):
  - `encryptAndStore(plainText: String)` - encrypts and stores, returns secret ID
  - `retrieveAndDecrypt(secretId: Long)` - retrieves and decrypts, returns plaintext
  - `updateSecret(secretId: Long, newPlainText: String)` - re-encrypts and updates
  - `deleteSecret(secretId: Long)` - deletes if not referenced elsewhere
  - Uses `EncryptionService` from common module
- [ ] Add integration with existing `EncryptionService`, `EncryptedSecret`, and `CryptoProvider`

**Technical Notes:**
- **Client-side only**: This table exists only in the local SQLDelight database (Desktop, Android, WASM)
- **Reusability**: Any table needing encrypted data can reference `EncryptedSecretTable` via foreign key
- **Envelope encryption**: Uses existing `EncryptionService` which implements envelope encryption (DEK encrypted with KEK)
- **Data model mapping**: SQLDelight table columns map directly to `EncryptedSecret` data class fields:
  - `EncryptedSecret(encryptedSecret, encryptedDEK, keyVersion)` ↔ DB columns
- **Lifecycle management**: Secrets are deleted only when no longer referenced by any table
- **Separation from domain entities**: Environment variables stored as reference (ID) not embedded encrypted string
- **Migration path**: Existing inline encrypted strings can be migrated to this table
- **Platform independence**: Works across Desktop, Android, and WASM (KMP compatible)
- **Key rotation support**: `keyVersion` field enables future key rotation without re-encrypting all data immediately
- **Example usage flow**:
  1. User enters environment variables in UI
  2. `EncryptedSecretService.encryptAndStore()` encrypts the JSON string
  3. Returns secret ID
  4. `LocalMCPServerLocalTable` stores the secret ID in `environmentVariablesSecretId` field
  5. When loading config, `EncryptedSecretService.retrieveAndDecrypt()` decrypts the environment variables
- **Future use cases**: 
  - API keys in tool configurations
  - Database passwords
  - OAuth tokens
  - Any sensitive configuration data

**Future Enhancements:**
- [ ] Add reference counting table to track which entities reference which secrets
- [ ] Implement automatic garbage collection of unreferenced secrets
- [ ] Add secret rotation mechanism (re-encrypt with new key version)
- [ ] Support bulk encryption/decryption operations
- [ ] Add secret expiration/TTL support

---

#### US1.1 - Local MCP Server ID Generation (Server-Side Minimal Storage)
**As a** system administrator
**I want** the server to generate unique IDs for local MCP servers
**So that** tool definitions can be consistently linked to MCP servers across client and server

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerTable` with minimal fields (server-side):
  - `id` (Long, PK) - unique identifier generated by server
  - `userId` (Long, FK) - owner of the MCP server configuration
  - **No other fields** - all configuration data stored client-side
- [ ] Add new `ToolType.MCP_LOCAL` enum value
- [ ] **OPTIONAL**: Add `ToolType.MCP_REMOTE` enum value for future remote MCP servers
- [ ] Update `ExposedDataManager` to include new tables
- [ ] Create client-side SQLDelight schema for full MCP server configuration:
  - Table: `LocalMCPServerLocalTable` (client-side database)
  - `id` (Long, PK) - matches server-generated ID
  - `name` (String)
  - `description` (String, nullable)
  - `command` (String) - executable command (e.g., "java", "uv", "docker")
  - `arguments` (String) - JSON array of command arguments
  - `environmentVariablesSecretId` (Long, nullable, FK to EncryptedSecretTable)
  - `workingDirectory` (String, nullable)
  - `isEnabled` (Boolean) - whether server is globally enabled (if false, tools cannot be enabled for any session)
  - `autoStartOnEnable` (Boolean, default: false) - start server when tool is enabled for session
  - `autoStartOnLaunch` (Boolean, default: false) - start server when application launches
  - `autoStopAfterInactivitySeconds` (Int, nullable) - auto-stop after inactivity (null = use default 300s, 0 = never stop)
  - `toolsEnabledByDefault` (Boolean, nullable) - whether tools from this server are enabled by default for NEW chat sessions (null = false)
  - `createdAt`, `updatedAt` (Long)

**Technical Notes:**
- **Server-side**: Only stores ID and userId for linkage purposes
- **Client-side**: Full configuration stored in SQLDelight database (KMP-compatible)
- Client requests ID from server when creating new MCP server configuration
- Client stores server-generated ID along with full config locally
- **Environment variables encryption**: Uses EncryptedSecretTable (US1.0) for reusable encrypted storage
  - Environment variables JSON encrypted and stored in EncryptedSecretTable
  - LocalMCPServerLocalTable references via `environmentVariablesSecretId` foreign key
  - Decrypted on-demand using EncryptedSecretService
- Each platform (Desktop, Android, WASM) has independent local storage
- No synchronization between platforms (different commands/paths per platform)
- SQLDelight supports all KMP targets (Desktop, Android, WASM)

---

#### US1.2 - Local MCP Server DAO Layer (Server & Client)
**As a** backend developer
**I want** DAO interfaces for local MCP server operations
**So that** services can generate IDs and manage linkages

**Server-Side Acceptance Criteria:**
- [ ] Create `LocalMCPServerDao` interface (server-side) with minimal methods:
  - `generateId(userId)` - creates new entry with server-generated ID, returns ID
  - `deleteById(id)` - deletes entry (cascade deletes tool linkages)
  - `getIdsByUserId(userId)` - returns list of server IDs owned by user
  - `existsById(id)` - checks if ID exists
- [ ] Implement Exposed-based DAO following existing patterns
- [ ] Add comprehensive error types (sealed classes)

**Client-Side Acceptance Criteria:**
- [ ] Create `LocalMCPServer` model class in `app` module (full configuration):
  - All fields from client-side SQLDelight schema, excluding `environmentVariablesSecretId`
  - This class is shared across all client platforms
  - Includes `environmentVariables` field (JsonObject) - decrypted in-memory representation
- [ ] Create `LocalMCPServerLocalDao` interface (client-side) with methods:
  - `insert(server)`, `update(server)`, `delete(serverId)`
  - `getById(serverId)`, `getAll(userId)`, `getAllEnabled(userId)`
  - Uses SQLDelight generated code
- [ ] Implement encryption/decryption for environment variables:
  - When saving: Call `EncryptedSecretService.encryptAndStore()` to create/update EncryptedSecret
  - Store returned secret ID in `environmentVariablesSecretId` field
  - When loading: Call `EncryptedSecretService.retrieveAndDecrypt()` to get plaintext
  - When deleting: Call `EncryptedSecretService.deleteSecret()` to clean up (if not referenced elsewhere)

**Technical Notes:**
- **Server DAO**: Simple ID generation and ownership tracking
- **Client DAO**: Full CRUD for local configuration storage
- Use `Either` return types for error handling on server-side
- SQLDelight generates type-safe DAO code for client-side
- `LocalMCPServer` model class resides in app module as it's only used on client-side
- Encryption/decryption delegated to EncryptedSecretService (US1.0)
- DAO layer remains unaware of encryption details - just stores/retrieves secret IDs
- Each platform has its own SQLDelight database instance

---

#### US1.3 - Local MCP Tool Definition Linkage
**As a** system
**I want** to link MCP tools to their source servers
**So that** I can track which server provides which tools

**Acceptance Criteria:**
- [ ] Create `LocalMCPToolDefinitionTable` junction table:
  - `toolDefinitionId` (FK to `ToolDefinitionTable`)
  - `mcpServerId` (FK to `LocalMCPServerTable`)
  - `mcpToolName` (String, nullable) - **FUTURE**: original tool name from MCP server (for name mapping)
  - Composite PK on (toolDefinitionId, mcpServerId)
- [ ] Update `ToolDefinitionTable` to support MCP tools:
  - Remove unique constraint on `name` field (tool names are NOT globally unique)
  - Add `isEnabledByDefault` field (Boolean, nullable) - controls default enablement for NEW chat sessions
    - `null` = use server-level default (`LocalMCPServerLocalTable.toolsEnabledByDefault`)
    - `true` = enable this tool by default for new sessions (overrides server default)
    - `false` = disable this tool by default for new sessions (overrides server default)
    - **Note**: This is separate from `ToolDefinition.isEnabled` which globally enables/disables the tool
- [ ] Create DAO methods for managing MCP tool linkages
- [ ] Add cascade delete behavior

**Technical Notes:**
- MCP tools are stored as regular `ToolDefinition` entries with `type = ToolType.MCP_LOCAL`
- The junction table tracks the MCP server source
- When MCP server is deleted, associated tool definitions are also deleted
- **FUTURE - Name Mapping**: The `mcpToolName` field enables optional mapping from LLM tool name to MCP server tool name
  - Field added to schema but feature not implemented in initial release
  - If `mcpToolName` is null, the tool definition name is used as-is
  - If `mcpToolName` is set, it maps: `ToolDefinition.name` (used by LLM) → `mcpToolName` (used by MCP server)
  - This allows users to simplify long/complex MCP tool names for LLM usage
- **Tool Name Uniqueness**: Tool names must be unique within enabled tools for a given chat session
  - This is enforced at the application level, not database level
  - Users are responsible for ensuring unique names within their account
  - The app should warn users if duplicate names are detected in enabled tools

---

#### (optional, skip) US1.4 - Local MCP Server Service Layer (Server-Side)
**As a** backend developer
**I want** a service layer for local MCP server ID generation
**So that** clients can obtain unique IDs for their local configurations

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerService` interface with methods:
  - `generateServerId(userId)` - creates new entry, returns server-generated ID
  - `deleteServer(serverId)` - deletes entry (validates ownership, cascades to tool linkages)
  - `getServerIdsByUser(userId)` - lists all server IDs owned by user
  - `validateOwnership(userId, serverId)` - checks if user owns server
- [ ] Implement service with minimal business logic
- [ ] Add validation for ownership

**Technical Notes:**
- Server only generates IDs and tracks ownership
- No configuration validation (that's client-side)
- Authorization checks handled at route layer
- Use server ID as primary identifier for operations
- Deletion cascades to LocalMCPToolDefinitionTable (removes linkages)

---

### Phase 2: MCP Client Management (Desktop Client)

#### US2.1 - Local MCP Server Process Manager
**As a** desktop application
**I want** to manage local MCP server processes
**So that** I can start/stop local MCP servers on demand

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerProcessManager` class (desktop-only) with:
  - `startServer(config: LocalMCPServer)` - launches process with STDIO
  - `stopServer(serverId)` - gracefully terminates process
  - `getServerStatus(serverId)` - returns running/stopped status
  - `restartServer(serverId)` - stops and starts server
- [ ] Implement process lifecycle management
- [ ] Handle process crashes and auto-restart (optional)
- [ ] Manage STDIO streams for communication

**Technical Notes:**
- Use `ProcessBuilder` for JVM platform
- Store process references in memory (Map<serverId, Process>)
- Implement proper cleanup on application shutdown
- Use `kotlinx.io` for stream handling

---

#### US2.2 - Local MCP Server Manager (High-Level Orchestration)
**As a** desktop application
**I want** a manager service to orchestrate MCP server workflows
**So that** I can coordinate between data, MCP operations, and API persistence

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerManager` class (desktop-only) with:
  - `testConnection(serverId)` - orchestrates connection testing
    - Gets config from repository
    - Calls MCPClientService to start and connect
    - Returns success/failure with tool count
  - `discoverTools(serverId)` - orchestrates tool discovery and persistence
    - Gets config from LocalMCPServerRepository
    - Calls MCPClientService to discover tools
    - Converts MCP Tool objects to ToolDefinition format
    - Calls ToolRepository to persist tools (ToolRepository handles API call)
    - Returns list of discovered tools
  - `refreshTools(serverId)` - orchestrates tool refresh
    - Gets config from LocalMCPServerRepository
    - Calls MCPClientService to discover current tools
    - Fetches existing tools from ToolRepository
    - Compares and identifies changes (new, updated, deleted)
    - Calls ToolRepository to persist changes (ToolRepository handles API call)
    - Returns summary of changes
- [ ] Inject dependencies:
  - `LocalMCPServerRepository` - for reading cached MCP server configs
  - `ToolRepository` - for persisting discovered tools
  - `MCPClientService` - for MCP operations (US2.3)
- [ ] Handle errors from all layers
- [ ] Provide detailed error messages for UI

**Technical Notes:**
- **High-level orchestration layer** between UI and MCP operations
- Coordinates data flow: LocalMCPServerRepository (configs) → MCPClientService (MCP ops) → ToolRepository (tool persistence)
- Handles data transformation (MCP Tool → ToolDefinition)
- Does not manage state (that's Repository's job)
- Does not manage processes (that's MCPClientService's job)
- Does not call API directly (that's Repository's job)
- Pure business logic and workflow coordination
- Called by ViewModels for user-initiated actions
- **Separation of concerns**: 
  - LocalMCPServerRepository for MCP server data
  - ToolRepository for tool data (including MCP tools)

---

#### US2.3 - MCP Client Service (MCP Operations Layer)
**As a** desktop application
**I want** a service to handle MCP-specific operations
**So that** I can manage MCP server processes and SDK interactions without coupling to data layer

**Acceptance Criteria:**
- [ ] Create `MCPClientService` class (desktop-only) with:
  - `startAndConnect(serverId, config)` - starts process and wraps MCP SDK client
    - Takes LocalMCPServer config as parameter (not fetched internally)
    - Calls LocalMCPServerProcessManager to start process
    - Creates MCP SDK Client instance with STDIO transport
    - Stores client reference (Map<serverId, Client>)
    - Returns success/failure
  - `discoverTools(serverId, config)` - discovers tools from running MCP server
    - Starts and connects if not already running
    - Calls `mcpSdkClient.listTools()`
    - Returns List<MCP Tool> objects (raw from SDK)
    - Does NOT convert or persist (that's LocalMCPServerManager's job)
  - `callTool(serverId, toolName, arguments)` - executes a tool
    - Calls `mcpSdkClient.callTool(toolName, arguments)`
    - Returns tool result or error
  - `stopServer(serverId)` - stops MCP server process
    - Disconnects MCP SDK client
    - Calls LocalMCPServerProcessManager to stop process
  - `getServerStatus(serverId)` - returns process status
    - Delegates to LocalMCPServerProcessManager
- [ ] Inject dependencies:
  - `LocalMCPServerProcessManager` - for process lifecycle
  - MCP SDK Client (created as needed, not injected)
- [ ] Handle MCP SDK errors
- [ ] Manage client connections (lifecycle, reconnection)

**Technical Notes:**
- **MCP-specific operations layer** - pure MCP SDK interactions
- No dependencies on Repository or API
- Takes config as parameter (separation of concerns)
- Wraps MCP SDK Client for easier testing and abstraction
- Manages process-to-client mapping (Map<serverId, Client>)
- Called by LocalMCPServerManager (US2.2), not directly by ViewModels
- Stateless except for active client connections

---

#### US2.4 - Local MCP Server Connection Testing UI
**As a** user
**I want** to test my local MCP server connection
**So that** I can verify it's configured correctly

**Acceptance Criteria:**
- [ ] Add "Test Connection" button to MCP server management UI
- [ ] Implement connection test flow:
  - User clicks button
  - ViewModel calls `localMCPServerManager.testConnection(serverId)`
  - Show loading indicator
  - Display success/failure result
  - Show discovered tool count on success
  - Show detailed error message on failure
- [ ] Add timeout handling (30 seconds)
- [ ] Provide user-friendly error messages

**Technical Notes:**
- Connection test is orchestrated by LocalMCPServerManager (US2.2)
- LocalMCPServerManager delegates MCP operations to MCPClientService (US2.3)
- Don't persist tools during test (that's a separate "Discover Tools" action)
- Provide detailed error messages for common issues (command not found, invalid args, etc.)
- Test should be idempotent
- This is a client-side operation (no server API endpoint needed)

---

### Phase 3: Tool Discovery & Synchronization

#### US3.1 - Local MCP Tool Discovery and Persistence
**As a** system
**I want** to persist discovered tools from local MCP servers
**So that** they can be used by the LLM

**Acceptance Criteria:**
- [ ] Create server-side tool persistence logic:
  - `createToolsForServer(serverId, toolDefinitions)` - batch creates tools with linkages
  - **Atomic transaction**: Creates ToolDefinition entries AND LocalMCPToolDefinitionTable linkages in single transaction
  - Ensures consistency (no partial failures - either all succeed or all rollback)
  - Sets `type = ToolType.MCP_LOCAL` for all created tools
  - Returns created tool definitions with IDs
- [ ] Convert MCP `Tool` objects to `ToolDefinition` entities:
  - Map MCP `inputSchema` to `ToolDefinition.inputSchema`
  - Map MCP `description` to `ToolDefinition.description`
  - Set `type = ToolType.MCP_LOCAL`
  - Store MCP-specific metadata in `config` JSON field
- [ ] Handle tool name conflicts (validation)
- [ ] Add proper error handling for duplicate tools

**Technical Notes:**
- **Client-side orchestration** happens in `LocalMCPServerManager` (US2.2):
  - LocalMCPServerManager calls MCPClientService (US2.3) to list tools from running MCP server
  - LocalMCPServerManager converts MCP tools to ToolDefinition format
  - LocalMCPServerManager calls ToolRepository.persistMCPTools()
  - ToolRepository calls ToolApi endpoint to persist tools (this user story)
- **Server-side persistence** (this user story):
  - Server validates and stores tool definitions
  - Server creates BOTH ToolDefinition AND LocalMCPToolDefinitionTable in **atomic transaction**
  - Server returns created tools to client
  - **Atomicity ensures**: Either all tools persist successfully, or none do (rollback)
- **Repository coordination** (client-side):
  - ToolRepository invalidates its own cache after successful API call
  - LocalMCPServerRepository doesn't need notification (it doesn't cache tools)
- Tools are discovered on client, persisted on server
- Discovery is triggered by user action (Discover Tools button)
- This is NOT automatic discovery - it's a user-initiated operation

---

#### US3.2 - Tool Refresh Mechanism
**As a** user  
**I want** to refresh the tool list from an MCP server  
**So that** I can get updated tools when the server changes

**Acceptance Criteria:**
- [ ] Add "Refresh Tools" button to MCP server management UI
- [ ] Implement refresh logic in `LocalMCPServerManager.refreshTools(serverId)`:
  - Get config from repository
  - Call MCPClientService to fetch current tools from MCP server
  - Fetch existing tools from repository/API
  - Compare tool lists (by name)
  - Identify: new tools, changed tools (schema), deleted tools
  - Call API to persist changes
- [ ] Preserve user's per-session tool enablement settings
- [ ] Handle tool schema changes gracefully
- [ ] Show summary of changes to user (X added, Y updated, Z removed)
- [ ] Emit events for UI updates

**Technical Notes:**
- Use tool name as the comparison key
- Don't delete tools that are currently enabled in active sessions (mark as deprecated instead)
- Log all changes for audit trail
- Refresh is orchestrated by LocalMCPServerManager (US2.2)
- Server provides batch update API endpoint

---

### Phase 4: MCP Tool Execution

#### US4.1 - Local MCP Tool Executor
**As a** system
**I want** an executor for local MCP tools
**So that** the LLM can invoke them during conversations

**Acceptance Criteria:**
- [ ] Create `LocalMCPToolExecutor` implementing `ToolExecutor` interface
- [ ] Implement `executeTool(toolDefinition, inputJson)` that:
  - Emits ChatEvent.MCPToolExecutionRequested (or ChatStreamEvent.MCPToolExecutionRequested)
  - Waits for tool result from Flow<ToolResult> parameter
  - Returns tool result or error
- [ ] Add to `ToolExecutorFactory` for `ToolType.MCP_LOCAL`
- [ ] Handle execution timeouts (configurable, default 60s)

**Technical Notes:**
- Local MCP tools execute on the client, not the server
- Tool execution request is sent via existing ChatService event emission mechanism
- Server waits for result from Flow<ToolResult> parameter (populated by WebSocket incoming messages)
- Use `withTimeoutOrNull` for timeout handling
- Implement proper error handling for client disconnection and timeouts

---

#### US4.2 - WebSocket Message Processing Integration
**As a** system  
**I want** to replace SSE with WebSocket on the message processing route
**So that** bidirectional communication enables MCP tool execution

**Acceptance Criteria:**
- [ ] Update POST /api/v1/sessions/{sessionId}/messages route to use WebSocket instead of SSE
- [ ] Add new ChatEvent and ChatStreamEvent types:
  - `ChatEvent.MCPToolExecutionRequested(toolCall, serverId, mcpToolName)`
  - `ChatStreamEvent.MCPToolExecutionRequested(toolCall, serverId, mcpToolName)`
- [ ] Create `ToolResult` data class:
  - `toolCallId` (Long) - matches ToolCall.id (internal database ID)
  - `result` (String) - JSON result from MCP server
  - `status` (ToolCallStatus) - SUCCESS or FAILED
  - `errorMessage` (String, nullable)
- [ ] Update ChatService interface:
  - Add `toolResultFlow: Flow<ToolResult>?` parameter to `processNewMessage()`
  - Add `toolResultFlow: Flow<ToolResult>?` parameter to `processNewMessageStreaming()`
- [ ] Implement WebSocket route handler:
  - Create Channel<ToolResult> for receiving tool results from client
  - Launch coroutine to receive WebSocket frames (incoming tool results)
  - Pass toolResultFlow to ChatService methods
  - Send ChatService events as WebSocket frames
  - Handle connection lifecycle (close after completion)
- [ ] Update ChatService implementation to:
  - Emit MCPToolExecutionRequested events when MCP tools need execution
  - Wait for results from toolResultFlow using `withTimeoutOrNull`
  - Continue message processing with tool results

**Technical Notes:**
- WebSocket frames use JSON serialization for all messages
- Client sends ToolResult objects as JSON
- Server sends ChatEvent/ChatStreamEvent objects as JSON
- Use Channel with BUFFERED capacity for tool results
- Implement proper cleanup when WebSocket connection closes
- Handle timeout scenarios (default 60s per tool call)
- Include session ID validation and authorization checks

---

#### US4.3 - Client-Side Local MCP Tool Execution Handler
**As a** desktop client
**I want** to execute local MCP tools when requested by the server
**So that** the LLM can use local tools

**Acceptance Criteria:**
- [ ] Create `LocalMCPToolExecutionHandler` that:
  - Receives MCPToolExecutionRequested events via WebSocket
  - Looks up the appropriate MCP client connection
  - Calls the tool using `LocalMCPClientWrapper.callTool()`
  - Sends ToolResult back to server via WebSocket
- [ ] Handle execution errors gracefully
- [ ] Implement timeout handling
- [ ] Log all tool executions

**Technical Notes:**
- Listen for WebSocket frames and deserialize to ChatEvent/ChatStreamEvent
- Filter for MCPToolExecutionRequested event type
- Execute tools asynchronously (launch coroutine per tool request)
- Validate that the requested tool exists on the server
- Check that the local MCP server is running before execution
- Parse and validate tool arguments
- Convert MCP tool results to ToolResult format
- Send ToolResult as JSON via WebSocket frame
- Handle concurrent tool executions (multiple tools may be requested in parallel)

---

### Phase 5: API & Routes

#### US5.1 - Local MCP Server API Routes (ID Generation)
**As a** frontend developer
**I want** REST API endpoints for local MCP server ID generation
**So that** clients can obtain unique IDs for their local configurations

**Acceptance Criteria:**
- [ ] Create `configureLocalMCPServerRoutes.kt` with minimal endpoints:
  - `POST /api/v1/mcp-servers/generate-id` - generate new server ID (returns ID only)
  - `GET /api/v1/mcp-servers/ids` - list all server IDs for user
  - `DELETE /api/v1/mcp-servers/{id}` - delete server ID (validates ownership, cascades to tool linkages)
- [ ] Add authorization checks (user must own server for delete)
- [ ] Implement minimal request/response DTOs (only ID and userId)

**Technical Notes:**
- Server does NOT store or validate configuration
- Only generates IDs and tracks ownership
- Client stores full configuration locally
- Follow existing route patterns
- Use `authenticate(AuthSchemes.USER_JWT)`
- Return appropriate HTTP status codes
- Include error handling

---

#### US5.2 - Local MCP Tool Management API Routes
**As a** frontend developer
**I want** REST API endpoints for local MCP tool management
**So that** users can manage tools from their local MCP servers

**Acceptance Criteria:**
- [ ] Add endpoints for local MCP tool management:
  - `GET /api/v1/mcp-servers/{id}/tools` - list tools for server
  - `POST /api/v1/mcp-servers/{id}/tools` - create tool for server (creates ToolDefinition and linkage)
  - `POST /api/v1/mcp-servers/{id}/tools/batch` - batch create tools (used by discovery process)
  - `PUT /api/v1/mcp-servers/{id}/tools/{toolId}/name` - update MCP tool name mapping (FUTURE)
- [ ] Reuse existing tool endpoints for other operations:
  - `PUT /api/v1/tools/{toolId}` - update tool definition (existing route)
  - `DELETE /api/v1/tools/{toolId}` - delete tool definition (existing route)
  - `GET /api/v1/tools/{toolId}` - get tool details (existing route)
- [ ] Add authorization checks
- [ ] Validate tool name uniqueness within user's enabled tools

**Technical Notes:**
- Tool discovery, refresh, and connection testing are client-side operations (not API routes)
  - These operations happen on the desktop client where the MCP server runs (via MCPClientService)
  - No server-side API endpoints needed for these operations
- **Batch creation endpoint** is critical for tool discovery:
  - LocalMCPServerManager discovers tools locally (via MCPClientService)
  - Converts them to ToolDefinition format
  - Calls ToolRepository.persistMCPTools() (US6.1A)
  - ToolRepository calls ToolApi.createMCPToolsForServer() (US6.2)
  - ToolApi calls POST /api/v1/mcp-servers/{id}/tools/batch
  - **Server creates BOTH ToolDefinitions and LocalMCPToolDefinitionTable linkages atomically**
  - Single transaction ensures consistency (no partial failures)
  - Returns created tools with IDs
- Creating a tool involves both `ToolDefinition` and `LocalMCPToolDefinitionTable` entries
- The name mapping endpoint (FUTURE) updates the `mcpToolName` field in the junction table

---

### Phase 6: Frontend Integration

#### US6.1 - Local MCP Server Repository (Client-Side Local Storage)
**As a** frontend developer
**I want** a repository for local MCP server data with local storage
**So that** ViewModels can access local MCP server state across platforms

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerRepository` interface with:
  - `val servers: StateFlow<DataState<List<LocalMCPServer>>>`
  - `loadServers()` - fetches from local SQLDelight database
  - `createServer(server)` - requests ID from API, stores full config locally
  - `updateServer(server)` - updates local SQLDelight database
  - `deleteServer(serverId)` - deletes from local DB and calls API to delete ID
  - `getToolsForServer(serverId)` - delegates to ToolRepository.getToolsByServerId()
- [ ] Implement repository with caching
- [ ] Handle API errors and map to `RepositoryError`
- [ ] Emit state updates for reactive UI

**Technical Notes:**
- Follow patterns from `SessionRepository`, `ToolRepository`
- Cache server list in StateFlow
- Invalidate cache on mutations
- **Scope**: Only manages LocalMCPServer data (not tools)
- **Tool persistence**: Handled by ToolRepository (US6.X - to be added)
  - `getToolsForServer()` delegates to ToolRepository.getToolsByServerId()
  - LocalMCPServerManager calls ToolRepository directly for tool persistence
- **Note**: `testConnection()`, `discoverTools()`, and `refreshTools()` are NOT repository methods
  - These operations are orchestrated by `LocalMCPServerManager` (US2.2)
  - LocalMCPServerManager reads MCP server configs from this repository
  - LocalMCPServerManager calls ToolRepository for tool persistence
  - Separation of concerns:
    - LocalMCPServerRepository = MCP server state management
    - ToolRepository = tool state management (including MCP tools)
    - LocalMCPServerManager = orchestration between the two

---

#### US6.1A - Extend Tool Repository for MCP Tools
**As a** frontend developer
**I want** ToolRepository to support MCP tool persistence
**So that** MCP tools can be managed consistently with other tools

**Acceptance Criteria:**
- [ ] Extend `ToolRepository` interface with MCP-specific methods:
  - `persistMCPTools(serverId, toolDefinitions)` - persist discovered MCP tools with linkages
    - Calls API batch endpoint to create ToolDefinitions and linkages atomically
    - Invalidates tool cache (new tools created)
    - Returns created tools or error
  - `getToolsByServerId(serverId)` - get all tools for a specific MCP server
    - Filters tools by serverId via LocalMCPToolDefinitionTable linkage
    - Returns cached tools if available
  - `refreshMCPTools(serverId, toolDefinitions)` - update tools for MCP server
    - Compares with existing tools
    - Calls API to add new, update changed, remove deleted
    - Invalidates cache
- [ ] Update ToolRepository implementation
- [ ] Handle API errors and map to `RepositoryError`
- [ ] Maintain cache consistency

**Technical Notes:**
- **Extends existing ToolRepository** (don't create new repository)
- Follow existing patterns from `SessionRepository`, `ToolRepository`
- **API batch endpoint**: `POST /api/v1/mcp-servers/{id}/tools/batch`
  - Server creates BOTH ToolDefinitions AND LocalMCPToolDefinitionTable linkages atomically
  - Single transaction ensures consistency
- **Cache strategy**: Invalidate entire tool cache when MCP tools change
  - Alternative: Partial cache invalidation (only affected server's tools)
  - Start with full invalidation for simplicity
- **No dependency on LocalMCPServerRepository** - ToolRepository is independent
- Called by LocalMCPServerManager (US2.2) for tool persistence

---

#### US6.2 - Tool API Client Extensions
**As a** frontend developer
**I want** ToolApi to support MCP tool batch operations
**So that** ToolRepository can persist MCP tools efficiently

**Acceptance Criteria:**
- [ ] Extend `ToolApi` interface with MCP batch endpoint:
  - `createMCPToolsForServer(serverId, toolDefinitions)` - batch create tools with linkages
    - Calls `POST /api/v1/mcp-servers/{id}/tools/batch`
    - Returns created tools with IDs
- [ ] Implement in `KtorToolApiClient` using Ktor
- [ ] Map HTTP errors to `ApiResourceError`

**Technical Notes:**
- **Extends existing ToolApi** (not LocalMCPServerApi)
- Tools are tool-related, so ToolApi is the right place
- Follow patterns from existing ToolApi methods
- Use existing Ktor client configuration

---

#### US6.3 - Local MCP Server API Client
**As a** frontend developer
**I want** an API client for local MCP server endpoints
**So that** LocalMCPServerRepository can communicate with the backend

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerApi` interface
- [ ] Implement `KtorLocalMCPServerApiClient` using Ktor
- [ ] Add methods for MCP server CRUD endpoints (no tool endpoints)
- [ ] Map HTTP errors to `ApiResourceError`

**Technical Notes:**
- Use existing Ktor client configuration
- Follow patterns from `SessionApi`, `ToolApi`
- **Does NOT include tool endpoints** (those are in ToolApi - US6.2)
- Only handles LocalMCPServer data operations

---

#### US6.4 - Local MCP Server Management UI
**As a** user
**I want** a UI to manage my local MCP servers
**So that** I can configure which tools are available

**Acceptance Criteria:**
- [ ] Create `LocalMCPServerManagementScreen` composable with:
  - List of configured local MCP servers
  - Add/Edit/Delete server dialogs
  - Test connection button with status indicator
  - Discover/Refresh tools buttons
  - Enable/Disable server toggle
- [ ] Create `LocalMCPServerViewModel` for state management
- [ ] Show server status (running/stopped/error)
- [ ] Display tool count for each server

**Technical Notes:**
- Use Material 3 components
- Follow existing UI patterns from Settings screens
- Show loading states during async operations
- Display error messages clearly

---

#### US6.5 - Local MCP Server Configuration Dialog
**As a** user
**I want** a dialog to configure local MCP server details
**So that** I can set up new servers or edit existing ones

**Acceptance Criteria:**
- [ ] Create dialog with fields:
  - Name (required)
  - Description (optional)
  - Command (required, with file picker)
  - Arguments (list of strings, add/remove)
  - Environment Variables (key-value pairs, add/remove)
  - Working Directory (optional, with directory picker)
  - Auto-start options (On Enable, On Launch)
  - Auto-stop timeout (nullable integer)
  - Tools enabled by default (nullable boolean)
- [ ] Validate all inputs before submission
- [ ] Show helpful error messages
- [ ] Support both create and edit modes

**Technical Notes:**
- Use `OutlinedTextField` for text inputs
- Implement dynamic list for arguments and env vars
- Add tooltips for complex fields
- Test with various MCP server types (Java, Python, Docker)
- Environment variables are encrypted client-side before sending to server

---

### Phase 7: Session Tool Configuration

#### US7.1 - Local MCP Tool Session Configuration
**As a** user
**I want** to enable/disable local MCP tools per session
**So that** I can control which tools are available in each conversation

**Acceptance Criteria:**
- [ ] Extend existing tool configuration dialog to show local MCP tools
- [ ] Group tools by local MCP server in the UI
- [ ] Show server status indicator next to MCP tools
- [ ] Disable tool toggle if server is globally disabled (`LocalMCPServer.isEnabled = false`)
- [ ] Disable tool toggle if tool is globally disabled (`ToolDefinition.isEnabled = false`)
- [ ] Update `SessionToolConfigTable` for MCP tools
- [ ] Warn user if duplicate tool names are detected in enabled tools

**Technical Notes:**
- Reuse existing `ToolConfigDialog` component
- Add server grouping logic
- Check server status before allowing enablement
- Show warning if enabling tool from stopped server
- Validate tool name uniqueness within enabled tools for the session
- **Global vs Session Enable/Disable**:
  - Global disable (`isEnabled = false`) prevents tool from being enabled in ANY session
  - Session enable/disable allows per-conversation customization when globally enabled
  - Users can manually enable/disable tools per session regardless of default settings

---

#### US7.2 - Local MCP Server Auto-Start
**As a** user
**I want** local MCP servers to auto-start when needed
**So that** I don't have to manually start them

**Acceptance Criteria:**
- [ ] Implement three auto-start modes (configured in `LocalMCPServerTable`):
  1. **On Demand (Always Active)**: Start server when LLM requests a tool call and server is not running
     - May be slow if server takes time to launch
     - No configuration needed - this is default behavior
  2. **On Enable**: Start server when tool is enabled for chat session
     - Configured via `autoStartOnEnable` field (Boolean, default: false)
     - Launches server proactively to avoid delays during tool calls
  3. **On Launch**: Start server when application launches
     - Configured via `autoStartOnLaunch` field (Boolean, default: false)
     - Ensures server is ready immediately
- [ ] Implement auto-stop logic:
  - Keep server running for a period after tool call completion (in case of subsequent calls)
  - Auto-stop after inactivity period (configured via `autoStopAfterInactivitySeconds`)
    - `null` = use default (300 seconds / 5 minutes)
    - `0` = never auto-stop
    - `> 0` = custom inactivity timeout
- [ ] Show notification when server auto-starts
- [ ] Handle auto-start failures gracefully

**Technical Notes:**
- Don't block UI during auto-start
- Retry failed starts with exponential backoff
- Log auto-start events
- Track last activity timestamp per server for auto-stop logic
- Reset inactivity timer on each tool call

---

### Phase 8: Advanced Features

#### US8.1 - Local MCP Tool Default Enablement for New Sessions
**As a** user
**I want** to set default enablement for MCP tools in new chat sessions
**So that** new sessions automatically have my preferred tools enabled

**Acceptance Criteria:**
- [ ] Implement two-level default enablement for NEW chat sessions:
  1. **Server-level default**: `LocalMCPServerTable.toolsEnabledByDefault` (Boolean, nullable)
     - `null` or `false` = tools from this server are disabled by default in new sessions
     - `true` = all tools from this server are enabled by default in new sessions
  2. **Tool-level override**: `ToolDefinitionTable.isEnabledByDefault` (Boolean, nullable)
     - `null` = use server-level default
     - `true` = enable this tool by default in new sessions (overrides server default)
     - `false` = disable this tool by default in new sessions (overrides server default)
- [ ] Create UI to configure default enablement at both levels
- [ ] Apply defaults when creating new sessions:
  - Check tool-level `isEnabledByDefault` first
  - If null, fall back to server-level `toolsEnabledByDefault`
  - If server-level is also null, default to disabled
  - **Important**: Only apply if tool is globally enabled (`ToolDefinition.isEnabled = true` AND `LocalMCPServer.isEnabled = true`)
- [ ] Allow per-tool override in session configuration (users can manually enable/disable at any time)

**Technical Notes:**
- Server-level default provides bulk configuration
- Tool-level override provides fine-grained control
- Apply defaults only to new sessions
- Don't modify existing session configurations
- The hierarchy: Tool override → Server default → Disabled
- **Distinction from Global Enable/Disable**:
  - `isEnabledByDefault` controls initial state for NEW sessions only
  - `isEnabled` (on both server and tool) controls whether tool CAN be enabled at all
  - Users can always manually change tool enablement per session (if globally enabled)

---

#### US8.2 - Local MCP Server Health Monitoring
**As a** user
**I want** to see the health status of my local MCP servers
**So that** I know if they're working correctly

**Acceptance Criteria:**
- [ ] Implement periodic health checks (every 30s)
- [ ] Check server process status
- [ ] Ping local MCP server via `listTools()` call
- [ ] Show status indicators in UI (green/yellow/red)
- [ ] Log health check failures

**Technical Notes:**
- Run health checks in background coroutine
- Don't spam server with requests
- Cache health status for UI
- Allow manual health check trigger

---

#### US8.3 - Local MCP Tool Execution Logs
**As a** user
**I want** to view logs of local MCP tool executions
**So that** I can debug issues and understand what tools are doing

**Acceptance Criteria:**
- [ ] Add logging to `LocalMCPToolExecutor`
- [ ] Create log viewer UI component
- [ ] Show:
  - Timestamp, tool name, server name
  - Input arguments, output result
  - Execution duration, status (success/error)
- [ ] Filter logs by server, tool, status
- [ ] Export logs to file

**Technical Notes:**
- Store logs in memory (limited size, e.g., last 1000)
- Don't persist logs to database (privacy)
- Sanitize sensitive data in logs
- Use structured logging format

---

## Implementation Phases

### Phase 1: Foundation (US1.0 - US1.4)
**Goal:** Database schema and backend services for local MCP server management, plus client-side encrypted secrets infrastructure
**Duration:** 1-2 weeks
**Dependencies:** None

**Key Deliverables:**
- Encrypted secrets storage infrastructure (US1.0)
- Server-side minimal storage for MCP servers (US1.1)
- Client and server DAO layers (US1.2)
- MCP tool linkage tables (US1.3)
- Server service layer for ID generation (US1.4)

### Phase 2: Client Infrastructure (US2.1 - US2.4)
**Goal:** Desktop client can manage local MCP server processes and orchestrate operations
**Duration:** 1-2 weeks
**Dependencies:** Phase 1

**Key Deliverables:**
- LocalMCPServerProcessManager for process lifecycle (US2.1)
- LocalMCPServerManager for high-level orchestration (US2.2)
- MCPClientService for MCP-specific operations (US2.3)
- Connection testing UI (US2.4)

### Phase 3: Tool Discovery (US3.1 - US3.2)
**Goal:** Discover and sync tools from local MCP servers
**Duration:** 1 week
**Dependencies:** Phase 2

### Phase 4: Tool Execution (US4.1 - US4.3)
**Goal:** LLM can execute local MCP tools via client
**Duration:** 1-2 weeks
**Dependencies:** Phase 3

### Phase 5: API Layer (US5.1 - US5.2)
**Goal:** REST API for local MCP server and tool management
**Duration:** 1 week
**Dependencies:** Phase 1, Phase 3

### Phase 6: Frontend (US6.1 - US6.5)
**Goal:** UI for local MCP server management
**Duration:** 1-2 weeks
**Dependencies:** Phase 5

**Key Deliverables:**
- LocalMCPServerRepository for MCP server state management (US6.1)
- ToolRepository extensions for MCP tool persistence (US6.1A)
- ToolApi extensions for batch operations (US6.2)
- LocalMCPServerApi for MCP server CRUD (US6.3)
- Management UI and configuration dialogs (US6.4, US6.5)

### Phase 7: Session Integration (US7.1 - US7.2)
**Goal:** Per-session tool configuration and auto-start
**Duration:** 1 week
**Dependencies:** Phase 4, Phase 6

### Phase 8: Polish (US8.1 - US8.3)
**Goal:** Advanced features and monitoring
**Duration:** 1 week
**Dependencies:** Phase 7

**Total Estimated Duration:** 9-12 weeks

---

## Technical Considerations

### Security
- **Process Isolation:** MCP servers run as separate processes with limited permissions
- **Input Validation:** Validate all tool arguments before execution
- **User Authorization:** Only server owner can modify configuration
- **Sandboxing:** Consider running MCP servers in containers (future enhancement)

### Performance
- **Connection Pooling:** Reuse MCP client connections
- **Lazy Loading:** Only start servers when needed
- **Caching:** Cache tool definitions to avoid repeated discovery
- **Timeouts:** Implement timeouts for all MCP operations

### Error Handling
- **Graceful Degradation:** Continue working if MCP server fails
- **Retry Logic:** Retry failed connections with exponential backoff
- **User Feedback:** Show clear error messages with actionable steps
- **Logging:** Comprehensive logging for debugging

### Testing
- **Unit Tests:** Test all service and DAO layers
- **Integration Tests:** Test MCP client wrapper with mock servers
- **E2E Tests:** Test full flow from UI to tool execution
- **Mock MCP Servers:** Create test servers for CI/CD

---

## Open Questions

1. **Multi-platform Support:** Should local MCP servers work on WASM?
   - **Answer:** No, desktop-only feature (requires process management)
2. **Server Sharing:** Should users be able to share local MCP server configurations?
   - **Answer:** Marked for future implementation due to encrypted environment variables (user-specific encryption key)
3. **Tool Versioning:** How to handle tool schema changes over time?
   - **Answer:** Refresh mechanism handles this
4. **Resource Limits:** Should we limit number of concurrent local MCP servers?
   - **Answer:** Yes, configurable limit (e.g., max 10 concurrent servers)
5. **Credential Management:** How to handle local MCP servers that need API keys?
   - **Answer:** Store in environment variables (encrypted client-side)
6. **Remote MCP Servers:** Should we support remote (HTTP-based) MCP servers?
   - **Answer:** Optional `ToolType.MCP_REMOTE` enum value added for future implementation

---

## Success Metrics

- [ ] Users can configure at least 3 different types of local MCP servers (Java, Python, Docker)
- [ ] Tool discovery completes in < 10 seconds for typical servers
- [ ] Tool execution latency < 5 seconds for simple tools
- [ ] Zero crashes from local MCP server failures
- [ ] 95% of tool executions succeed on first try
- [ ] Auto-start mechanisms work reliably (on-demand, on-enable, on-launch)
- [ ] Tool name uniqueness validation prevents LLM confusion
- [ ] Environment variable encryption protects sensitive credentials
- [ ] Encrypted secrets infrastructure is reusable for other sensitive fields (API keys, tokens, etc.)

---

## References

- [MCP Kotlin SDK Reference](../docs/MCP/Kotlin-SDK/Reference.md)
- [Current Tool Architecture](../server/src/main/kotlin/eu/torvian/chatbot/server/service/tool/)
- [Ownership Patterns](../server/src/main/kotlin/eu/torvian/chatbot/server/data/tables/)

