# Local MCP Servers - Epic Breakdown

## Executive Summary

This document breaks down the **NF.EA1 - Local MCP Servers** epic into implementable user stories. The epic enables LLMs to call local tools via the Model Context Protocol (MCP), allowing access to real-time data and external actions.

**Total User Stories**: 26 (across 8 implementation phases)

**Implementation Status** (as of 2025-12-11):
- ✅ **COMPLETED**: 19 user stories (73%)
- ❌ **NOT IMPLEMENTED**: 7 user stories (27%)

### Completed Components:
- ✅ **Phase 1 (Backend)**: All 6 user stories completed
  - US1.0: Encrypted Secret Storage Infrastructure
  - US1.1: Local MCP Server ID Generation
  - US1.2: Local MCP Server DAO Layer
  - US1.3: Local MCP Tool Definition Linkage & DAO
  - US1.3A: Local MCP Tool Definition Model
  - US1.3B: Local MCP Tool Definition Service Layer
  - US1.4: Local MCP Server Service Layer
- ✅ **Phase 2 (Client Infrastructure)**: 3 of 4 completed
  - US2.1: LocalMCPServerProcessManager (Desktop only)
  - US2.2: LocalMCPServerManager (orchestration layer)
  - US2.3: MCPClientService (Desktop/Android)
- ✅ **Phase 5 (API Routes)**: 2 of 2 completed
  - US5.1: Local MCP Server API Routes
  - US5.2: Local MCP Tool Management API Routes
- ✅ **Phase 6 (Frontend)**: 5 of 5 completed ✅ **ALL COMPLETE**
  - US6.1: LocalMCPServerRepository
  - US6.1A: LocalMCPToolRepository
  - US6.2: LocalMCPToolApi
  - US6.4: Local MCP Server Management UI ✅ **NEW** (2025-12-10)
  - US6.5: Local MCP Server Configuration Dialog ✅ **NEW** (2025-12-10)
- ✅ **Phase 7 (Tool Execution)**: 2 of 3 completed
  - US7.1: Local MCP Tool Session Configuration ✅ **NEW** (2025-12-11)
  - US7.3: Local MCP Tool Execution via WebSocket

### Missing Components:
- ❌ **US7.2**: Session-level auto-start for MCP servers
- ❌ **US8.1-8.3**: Advanced features (default enablement, health monitoring, logs)
- ❌ **US2.4, US3.1-3.2**: Tool discovery and refresh UI/workflows

**Key Architecture**: 
- **Client-Side Local Storage**: MCP server configurations stored locally using SQLDelight (Desktop, Android, WASM)
- **Server-Side Minimal Storage**: Server only generates unique IDs and tracks ownership (linkage support)
- **US2.2 (LocalMCPServerManager)** ✅ - High-level orchestration layer (coordinates workflows between repositories and services)
- **US2.3 (MCPClientService)** ✅ - MCP-specific operations layer (no Repository/API dependencies)
- **US6.1A (LocalMCPToolRepository)** ✅ - Dedicated repository for MCP tool persistence (separate from ToolRepository)
- **US6.2 (LocalMCPToolApi)** ✅ - Dedicated API client for MCP tool endpoints (separate from ToolApi)
- **LocalMCPToolDefinition Model** ✅ - Dedicated model class for MCP tools (extends ToolDefinition sealed class)
- Clean separation: UI → Manager → (MCP Server Repo + MCP Tool Repo) → MCP Operations → Process Management
- **Clean separation of concerns**:
  - **LocalMCPServerRepository** = MCP server configs (local storage)
  - **LocalMCPToolRepository** = MCP tools (server storage via LocalMCPToolApi)
  - **ToolRepository** = Non-MCP tools only (unchanged)

---

## Epic Definition

### NF.EA1 - Local MCP Servers (Epic)

**Description**: As a user, I want the LLM to be able to call local tools via the Model Context Protocol (MCP), so that the LLM can access real-time data and perform actions in the external world (such as searching the web, accessing local files, controlling GitHub repos, etc.).

**Estimate**: XL (9-12 weeks)

**Acceptance Criteria**:

#### MCP Server Configuration
- [ ] The user can configure their own local (STDIO) MCP servers with: **PARTIALLY IMPLEMENTED** (backend complete, UI missing)
  - ✅ A name for the server (unique per user)
  - ✅ An executable command to launch the server (e.g., "java", "uv", "docker")
  - ✅ Arguments to pass to the command (as array)
  - ✅ Environment variables to set before launching the server (e.g., GitHub access token)
    - ✅ **Encrypted client-side** with user-provided key for security
  - ✅ A working directory to launch the server in (optional)
  - ✅ Auto-start configuration:
    - ✅ `autoStartOnEnable` - start when tool is enabled for session
    - ✅ `autoStartOnLaunch` - start when application launches
    - ✅ `autoStopAfterInactivitySeconds` - auto-stop after inactivity
  - ✅ Default tool enablement for new sessions:
    - ✅ `toolsEnabledByDefault` - whether tools from this server are enabled by default

#### MCP Server Management
- [ ] The user can test the connection to an MCP server **PARTIALLY IMPLEMENTED** (orchestration layer complete, UI missing)
  - ✅ LocalMCPServerManager.testConnection() orchestrates connection testing workflow
  - ✅ Tests connection by listing available tools via MCP SDK
  - ✅ Returns success/failure with tool count
  - ❌ UI not implemented (no test button in management screen)
  - Client-side operation (no server API needed)
- [ ] The user can discover tools from an MCP server **PARTIALLY IMPLEMENTED** (orchestration layer complete, UI missing)
  - ✅ LocalMCPServerManager.createServer() discovers and persists tools during server creation
  - ✅ Lists all available tools via MCP SDK (MCPClientService.discoverTools)
  - ✅ Converts MCP SDK Tool objects to LocalMCPToolDefinition format
  - ✅ Persists tools to database (ToolDefinitionTable + LocalMCPToolDefinitionTable)
  - ✅ Server creates both entries atomically (single transaction)
  - ❌ UI not implemented (no discover tools button in management screen)
- [ ] The user can refresh the list of tools from an MCP server **PARTIALLY IMPLEMENTED** (orchestration layer complete, UI missing)
  - ✅ LocalMCPServerManager.refreshTools() orchestrates differential refresh workflow
  - ✅ Discovers current tools via MCP SDK
  - ✅ Compares current tools with existing tools (LocalMCPToolRepository.refreshMCPTools)
  - ✅ Adds new tools, updates changed tools, removes deleted tools
  - ✅ Returns summary of changes (added/updated/deleted counts)
  - ❌ Preserves user's per-session tool enablement settings (not implemented)
  - ❌ UI not implemented (no refresh tools button in management screen)
- [x] MCP servers can be started/stopped from within the application **IMPLEMENTED** (Desktop/Android only)
  - ✅ LocalMCPServerManager.startServer() orchestrates server start workflow
  - ✅ LocalMCPServerManager.stopServer() orchestrates server stop workflow
  - ✅ Process lifecycle managed by LocalMCPServerProcessManager (client-side)
  - ✅ MCP SDK client management by MCPClientService
  - ✅ Process status tracking (running/stopped/error)
  - ✅ Graceful shutdown and cleanup
  - ❌ UI not implemented (no start/stop buttons in management screen)

#### Data Persistence
- [x] MCP server configurations are stored **locally on the client machine** (using SQLDelight) **IMPLEMENTED**
  - ✅ Each platform (Desktop, Android, WASM) has its own independent storage
  - ✅ Configurations are **NOT synchronized** between platforms (different commands/paths per platform)
  - ✅ Environment variables are encrypted client-side using CryptoProvider
  - ✅ WASM platform: Local storage only (no MCP server execution support)
- [x] Server-side storage for MCP servers (minimal): **IMPLEMENTED**
  - ✅ Stores only `id` and `userId` in LocalMCPServerTable (for linkage purposes)
  - ✅ Server generates unique ID when client creates an MCP server configuration
  - ✅ Client stores this ID along with full configuration locally
  - ✅ Enables consistent tool-to-server linkage across client and server
- [x] MCP tools are stored in the tool definitions table (ToolDefinitionTable) **on the server** **IMPLEMENTED**
  - ✅ Type: `ToolType.MCP_LOCAL`
  - ✅ Linked to MCP server via LocalMCPToolDefinitionTable (junction table)
  - ✅ **Tool names are NOT globally unique** (only unique within session's enabled tools)
  - ✅ Support for optional name mapping via `mcpToolName` field (future feature)

#### Session-Level Tool Configuration
- [ ] The user can enable/disable MCP servers for specific chat sessions **NOT IMPLEMENTED**
  - Per-session configuration via SessionToolConfigTable
  - Only globally enabled tools can be enabled for sessions
- [ ] The user can select which tools from each MCP server are enabled for the current chat session **NOT IMPLEMENTED**
  - Individual tool enable/disable per session
  - Tools must be both globally enabled AND session-enabled to be available
- [ ] The user can configure default tool enablement for new sessions **NOT IMPLEMENTED**
  - Server-level default: `LocalMCPServerTable.toolsEnabledByDefault`
  - Tool-level override: `ToolDefinitionTable.isEnabledByDefault`
  - Hierarchy: Tool override → Server default → Disabled

#### UI & User Experience
- [ ] The user can view and manage MCP servers from the UI **NOT IMPLEMENTED**
  - List of configured servers with status (running/stopped/error)
  - Add/Edit/Delete server operations
  - Test connection and discover/refresh tools buttons
  - Enable/Disable server toggle
- [ ] The user can view the list of available tools from each MCP server **NOT IMPLEMENTED**
  - Tool count displayed per server
  - Tool details (name, description, schema)
  - Enable/disable tools for current session

#### LLM Integration & Tool Execution
- [x] The LLM can request to call tools exposed by local MCP servers **IMPLEMENTED**
  - ✅ LocalMCPExecutor handles MCP tool execution requests
  - ✅ Integrated into ChatService for `ToolType.MCP_LOCAL`
- [x] Tool execution uses WebSocket for bidirectional communication **IMPLEMENTED**
  - ✅ **Server-to-Client**: Tool execution requests via `MessageStreamEvent.LocalMCPToolCallReceived`
  - ✅ **Client-to-Server**: Tool results via `LocalMCPToolCallResult` objects
  - ✅ WebSocket handles both streaming and tool execution
  - ✅ Single WebSocket connection per message session
- [x] The client executes MCP tools locally on behalf of the LLM **IMPLEMENTED**
  - ✅ LocalMCPToolCallMediator receives execution requests via WebSocket
  - ✅ Calls MCPClientService to execute tool on local MCP server
  - ✅ Sends results back to server via WebSocket
- [x] The server receives tool results and forwards them to the LLM **IMPLEMENTED**
  - ✅ ChatService collects tool results from Flow parameter
  - ✅ Includes results in next LLM request
  - ✅ Supports multi-turn tool calling loops

#### Architecture & Technical Details
- [x] MCP clients are managed by the desktop application **FULLY IMPLEMENTED**
  - ✅ LocalMCPServerProcessManager launches MCP server processes (STDIO)
  - ✅ MCPClientService wraps MCP SDK Client for communication
  - ✅ LocalMCPServerManager orchestrates high-level workflows
  - ✅ Reactive serverOverviews StateFlow combines data from all layers
- [x] Clean separation of concerns by entity type: **FULLY IMPLEMENTED**
  - ✅ LocalMCPServerRepository manages MCP server configuration data
  - ✅ LocalMCPToolRepository manages MCP tool data
  - ✅ ToolRepository continues to manage non-MCP tools only
  - ✅ No cross-repository dependencies (clean separation achieved)
- [x] Dedicated model for MCP tools: **IMPLEMENTED**
  - ✅ **LocalMCPToolDefinition** - extends ToolDefinition sealed class
  - ✅ Includes `serverId`, `mcpToolName`, and `isEnabledByDefault` fields
  - ✅ Type is fixed to `ToolType.MCP_LOCAL`
  - ✅ **MiscToolDefinition** - for non-MCP tools
- [x] Process lifecycle management **FULLY IMPLEMENTED**
  - ✅ Auto-start modes: On Demand (default), On Enable, On Launch (fields exist in schema)
  - ✅ Auto-stop after configurable inactivity (implemented in MCPClientService)
    - ✅ Automatic timer reset on tool activity
    - ✅ Configurable timeout per server (`effectiveAutoStopSeconds`)
    - ✅ Respects `neverAutoStop` flag
  - ✅ Graceful shutdown and cleanup
- [ ] Health monitoring and logging **PARTIALLY IMPLEMENTED**
  - ✅ Server health status tracking (MCPClientService.pingClient)
  - ✅ Process status tracking (ProcessStatus enum)
  - ✅ Connection status tracking (isConnected, isResponsive)
  - ✅ Activity tracking (lastActivityAt timestamp)
  - ❌ Tool execution logs (not persisted - only in application logs)
  - ❌ Error notifications UI (not implemented)

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

4. **Separate Repository and API for MCP Tools** (NEW):
   - **LocalMCPToolRepository** (US6.1A): Dedicated repository for MCP tools, separate from ToolRepository
   - **LocalMCPToolApi** (US6.2): Dedicated API client for MCP tool endpoints, separate from ToolApi
   - **LocalMCPToolDefinition Model**: Dedicated model class extending ToolDefinition sealed class
     - Includes `serverId: Long` - directly embedded in the model
     - Includes `mcpToolName: String?` - for optional name mapping
     - Includes `isEnabledByDefault: Boolean?` - per-tool default enablement
     - Type is fixed to `ToolType.MCP_LOCAL`
   - **Rationale**:
     - **Clear separation of concerns**: MCP tools vs non-MCP tools are different domains
     - **Type safety**: Sealed class ensures compile-time safety for tool types
     - **Independent evolution**: Can add MCP-specific features without affecting existing tool system
     - **Better maintainability**: Each repository/API has single responsibility
     - **Reduced coupling**: Changes to MCP tools don't affect non-MCP tools and vice versa
     - **Easier testing**: Mock only what you need for each test scenario
     - **Clear API boundaries**: MCP endpoints under `/api/v1/local-mcp-tools/...`
     - **Embedded serverId**: No need for separate junction table lookups in most cases
   - **Benefits**:
     - ToolRepository remains unchanged (existing functionality preserved)
     - LocalMCPToolRepository has specialized methods for MCP operations (batch persist, refresh, server linkage)
     - Independent caching strategies for MCP vs non-MCP tools
     - Map-based cache structure (serverId → List<LocalMCPToolDefinition>) for efficient lookups
     - Clear ownership and responsibilities
     - Follows Single Responsibility Principle
   - **Architecture**: UI → LocalMCPServerManager → (LocalMCPServerRepository + LocalMCPToolRepository) → (LocalMCPServerApi + LocalMCPToolApi) → Server

5. **Security & Privacy**:
   - Environment variables encrypted with CryptoProvider (may contain API keys/tokens)
   - **Reusable encrypted storage**: Separate EncryptedSecretTable for storing encrypted data
     - Envelope encryption (DEK encrypted with KEK)
     - Other tables reference encrypted secrets via foreign key
     - Enables consistent encryption approach across different sensitive fields
     - Supports future key rotation without immediate re-encryption
   - Local storage on client eliminates need for server-side decryption
   - Each platform manages its own encryption keys

6. **Future Features Marked**:
   - Group-based sharing not applicable (configs are local per platform)
   - Access control DAOs removed (not needed for local storage)

7. **Auto-Start Modes**:
   - **On Demand**: Start when LLM requests tool call (default behavior)
   - **On Enable**: Start when tool enabled for session (`autoStartOnEnable` field)
   - **On Launch**: Start when application launches (`autoStartOnLaunch` field)
   - Auto-stop after configurable inactivity period

8. **Two-Level Default Enablement for New Chat Sessions**:
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
Tools discovered → LocalMCPServerManager calls LocalMCPToolRepository to persist
  ↓
LocalMCPToolRepository → LocalMCPToolApi → Server (stores tools + linkages atomically)
  ↓
LocalMCPToolRepository invalidates cache (new tools created)
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
  - **LocalMCPToolRepository (server API for MCP tool storage via LocalMCPToolApi) - NEW**
  - ToolRepository (server API for non-MCP tool storage - unchanged)
- **Network Layer**: 
  - LocalMCPServerApi (server communication for MCP server IDs)
  - **LocalMCPToolApi (server communication for MCP tools) - NEW**
  - ToolApi (server communication for non-MCP tools - unchanged)
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
│     │ 8. Persist tools via LocalMCPToolRepository                       │
│     ▼                                                                │
│  ┌──────────────────────┐                                          │
│  │  LocalMCPToolRepository   │ ◄──── NEW: Separate from ToolRepository  │
│  │  (US6.1A)            │                                          │
│  └──┬───────────────────┘                                          │
│     │                                                                │
│     │ 9. Call LocalMCPToolApi to persist tools + linkages               │
│     ▼                                                                │
│  ┌──────────────────────┐                                          │
│  │     LocalMCPToolApi       │ ◄──── NEW: Separate from ToolApi         │
│  │  (US6.2)             │                                          │
│  └──────────┬───────────┘                                          │
│             │                                                        │
│             │ HTTP POST /api/v1/local-mcp-servers/{id}/tools/batch      │
│             ▼                                                        │
│  Server creates tools + linkages atomically                        │
│             │                                                        │
│             │ 10. Success response                                 │
│             ▼                                                        │
│  ┌──────────────────────┐                                          │
│  │  LocalMCPToolRepository   │                                          │
│  │  (invalidate cache)  │                                          │
│  └──────────────────────┘                                          │
│                                                                      │
│  11. Return created tools to Manager                               │
│                                                                      │
└─────────────┼────────────────────────────────────────────────────────┘
              │ HTTP POST /api/v1/local-mcp-servers/{id}/tools/batch
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
   - Does NOT orchestrate operations (that's LocalMCPServerManager)
   - **Storage**: Platform-specific SQLDelight database (Desktop, Android, WASM)
   - **Encryption**: Uses CryptoProvider for environment variables

2. **LocalMCPToolRepository** (client-side - NEW):
   - **Dedicated repository for MCP tools only** (separate from ToolRepository)
   - Manages MCP tool data via LocalMCPToolApi
   - **New MCP-specific methods** (US6.1A):
     - `persistMCPTools(serverId, toolDefinitions)` - persist with linkages via API
     - `getToolsByServerId(serverId)` - filter tools by MCP server
     - `refreshMCPTools(serverId, toolDefinitions)` - update MCP server tools
   - Calls LocalMCPToolApi for all MCP tool operations
   - Invalidates cache when MCP tools change
   - No dependency on LocalMCPServerRepository or ToolRepository
   - **Caches MCP tools separately** from non-MCP tools

3. **ToolRepository** (client-side - UNCHANGED):
   - Continues to manage **non-MCP tools only**
   - No changes needed for MCP implementation
   - Existing functionality preserved

4. **LocalMCPServerManager** (client-side, NEW - US2.2):
   - High-level orchestration service
   - Handles complete user workflows (test, discover, refresh)
   - Coordinates between LocalMCPServerRepository, LocalMCPToolRepository, and MCPClientService
   - Gets MCP server configs from LocalMCPServerRepository
   - Calls MCPClientService for MCP operations (passing config as parameter)
   - Calls LocalMCPToolRepository to persist discovered tools
   - Returns results to ViewModels
   - **Dependencies**: LocalMCPServerRepository, LocalMCPToolRepository, MCPClientService
   - **Clean separation**: MCP server data vs MCP tool data vs non-MCP tool data

5. **MCPClientService** (client-side, NEW - US2.3):
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
10. **LocalMCPServerManager calls LocalMCPToolRepository** to persist tools:
   - `LocalMCPToolRepository.persistMCPTools(serverId, toolDefinitions)`
11. **LocalMCPToolRepository calls LocalMCPToolApi** to persist tools + linkages atomically:
   - `LocalMCPToolApi.createMCPToolsForServer(serverId, toolDefinitions)`
12. Server stores tools and linkages in DB (atomic transaction)
13. **LocalMCPToolRepository invalidates its cache** (new tools created)
14. LocalMCPToolRepository returns created tools to LocalMCPServerManager
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

A: Client-side discovery, server-side persistence via LocalMCPToolRepository:
1. LocalMCPServerManager calls MCPClientService.discoverTools(serverId, config)
2. MCPClientService starts process and wraps MCP SDK Client
3. MCPClientService calls mcpSdkClient.listTools() → returns List<MCP Tool>
4. LocalMCPServerManager converts MCP tools to ToolDefinition format
5. **LocalMCPServerManager calls LocalMCPToolRepository**: `LocalMCPToolRepository.persistMCPTools(serverId, toolDefinitions)`
6. **LocalMCPToolRepository calls LocalMCPToolApi**: `LocalMCPToolApi.createMCPToolsForServer(serverId, toolDefinitions)`
7. **LocalMCPToolApi calls**: `POST /api/v1/local-mcp-tools/batch`
8. Server validates and stores in DB (ToolDefinitions + linkages) atomically
9. **LocalMCPToolRepository invalidates its cache** (new tools created)
10. LocalMCPToolRepository returns created tools to Manager
11. UI shows updated tool list

**Q: Why have a separate LocalMCPToolRepository instead of extending ToolRepository?**

A: Clean separation of concerns and better maintainability:
- **LocalMCPToolRepository**: Dedicated to MCP tools only
  - Works with `LocalMCPToolDefinition` model (type-safe, includes serverId)
  - Specialized methods for MCP tool operations (discover, refresh, batch persist)
  - Uses LocalMCPToolApi for MCP-specific endpoints
  - Map-based cache structure (`Map<Long, List<LocalMCPToolDefinition>>`) for efficient server-based lookups
  - Can evolve MCP tool features without affecting non-MCP tools
- **ToolRepository**: Continues to handle non-MCP tools only
  - Works with `MiscToolDefinition` model
  - No changes needed for MCP implementation
  - Existing functionality preserved
  - Cleaner separation of responsibilities
- **Benefits**:
  - Type safety: LocalMCPToolDefinition vs MiscToolDefinition enforced at compile time
  - Easier to understand and maintain
  - Better testability (mock only what you need)
  - Clear ownership of responsibilities
  - Reduces coupling between MCP and non-MCP tool systems
  - Follows Single Responsibility Principle

**Q: Why have a separate LocalMCPToolApi instead of extending ToolApi?**

A: Same reasoning as LocalMCPToolRepository:
- **LocalMCPToolApi**: MCP-specific API endpoints
  - Works with `LocalMCPToolDefinition` model in requests/responses
  - Batch operations for MCP tool discovery
  - Server-based operations (get all tools for server, delete all for server)
  - Dedicated endpoint structure: `/api/v1/local-mcp-tools/...`
  - Can add MCP-specific features without affecting existing ToolApi
- **ToolApi**: Unchanged, handles non-MCP tools
  - Works with generic ToolDefinition or MiscToolDefinition
- **Benefits**:
  - Clear API boundaries
  - Type-safe operations with LocalMCPToolDefinition
  - Independent versioning and evolution
  - Easier to document and understand
  - No risk of breaking existing tool functionality

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

#### US1.0 - Encrypted Secret Storage Infrastructure (Client-Side) ✅ **COMPLETED**
**As a** developer
**I want** a reusable mechanism to store encrypted secrets in the database
**So that** sensitive data like environment variables can be stored securely and referenced from multiple tables

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create client-side SQLDelight schema `EncryptedSecretTable`:
  - `id` (Long, PK, auto-increment) - unique identifier for the encrypted secret
  - `encryptedSecret` (String, not null) - the secret encrypted with DEK, Base64 encoded
  - `encryptedDEK` (String, not null) - the DEK encrypted with KEK, Base64 encoded
  - `keyVersion` (Int, not null) - version of KEK used for encryption
  - `createdAt` (Long, not null) - creation timestamp
  - `updatedAt` (Long, not null) - last update timestamp
- [x] Create `EncryptedSecretLocalDao` interface (client-side) with methods:
  - `insert(encryptedSecret: EncryptedSecret)` - creates new encrypted secret, returns generated ID
  - `update(id: Long, encryptedSecret: EncryptedSecret)` - updates existing encrypted secret
  - `getById(id: Long)` - retrieves encrypted secret by ID
  - `deleteById(id: Long)` - deletes encrypted secret (with cascade handling)
  - `deleteUnreferenced()` - deletes orphaned secrets not referenced by any table
- [x] Implement SQLDelight queries for CRUD operations
- [x] Add reference counting mechanism (optional) to track usage
- [x] Create helper service `EncryptedSecretService` (client-side):
  - `encryptAndStore(plainText: String)` - encrypts and stores, returns secret ID
  - `retrieveAndDecrypt(secretId: Long)` - retrieves and decrypts, returns plaintext
  - `updateSecret(secretId: Long, newPlainText: String)` - re-encrypts and updates
  - `deleteSecret(secretId: Long)` - deletes if not referenced elsewhere
  - Uses `EncryptionService` from common module
- [x] Add integration with existing `EncryptionService`, `EncryptedSecret`, and `CryptoProvider`

**Implementation Details:**
- ✅ SQLDelight schema created at `app/src/commonMain/sqldelight/eu/torvian/chatbot/app/database/EncryptedSecretTable.sq`
- ✅ DAO implemented at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/database/dao/EncryptedSecretLocalDaoImpl.kt`
- ✅ Service implemented at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/misc/EncryptedSecretServiceImpl.kt`
- ✅ Comprehensive tests at `app/src/desktopTest/kotlin/eu/torvian/chatbot/app/database/dao/EncryptedSecretLocalDaoTest.kt`

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

#### US1.1 - Local MCP Server ID Generation (Server-Side Minimal Storage) ✅ **COMPLETED**
**As a** system administrator
**I want** the server to generate unique IDs for local MCP servers
**So that** tool definitions can be consistently linked to MCP servers across client and server

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `LocalMCPServerTable` with minimal fields (server-side):
  - `id` (Long, PK) - unique identifier generated by server
  - `userId` (Long, FK) - owner of the MCP server configuration
  - **No other fields** - all configuration data stored client-side
- [x] Add new `ToolType.MCP_LOCAL` enum value
- [ ] **OPTIONAL**: Add `ToolType.MCP_REMOTE` enum value for future remote MCP servers (DEFERRED)
- [x] Update `ExposedDataManager` to include new tables
- [x] Create client-side SQLDelight schema for full MCP server configuration:
  - Table: `LocalMCPServerLocalTable` (client-side database)
  - `id` (Long, PK) - matches server-generated ID
  - `userId` (Long) - owner of the MCP server configuration
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

**Implementation Details:**
- ✅ Server-side table at `server/src/main/kotlin/eu/torvian/chatbot/server/data/tables/LocalMCPServerTable.kt`
- ✅ Client-side schema at `app/src/commonMain/sqldelight/eu/torvian/chatbot/app/database/LocalMCPServerLocalTable.sq`
- ✅ `ToolType.MCP_LOCAL` enum added to `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/tool/ToolType.kt`
- ✅ Model class at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/domain/models/LocalMCPServer.kt`

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

#### US1.2 - Local MCP Server DAO Layer (Server & Client) ✅ **COMPLETED**
**As a** backend developer
**I want** DAO interfaces for local MCP server operations
**So that** services can generate IDs and manage linkages

**Status**: ✅ **FULLY IMPLEMENTED**

**Server-Side Acceptance Criteria:**
- [x] Create `LocalMCPServerDao` interface (server-side) with minimal methods:
  - `generateId(userId)` - creates new entry with server-generated ID, returns ID
  - `deleteById(id)` - deletes entry (cascade deletes tool linkages)
  - `getIdsByUserId(userId)` - returns list of server IDs owned by user
  - `existsById(id)` - checks if ID exists
  - `validateOwnership(id, userId)` - validates user owns the server (ADDED during implementation)
- [x] Implement Exposed-based DAO following existing patterns
- [x] Add comprehensive error types (sealed classes)

**Client-Side Acceptance Criteria:**
- [x] Create `LocalMCPServer` model class in `app` module (full configuration):
  - All fields from client-side SQLDelight schema, excluding `environmentVariablesSecretId`
  - This class is shared across all client platforms
  - Includes `environmentVariables` field (Map<String, String>) - decrypted in-memory representation
- [x] Create `LocalMCPServerLocalDao` interface (client-side) with methods:
  - `insert(server)`, `update(server)`, `delete(serverId)`
  - `getById(serverId)`, `getAll()`, `getAllEnabled()`, `existsByName(name)`
  - Uses SQLDelight generated code
- [x] Implement encryption/decryption for environment variables:
  - When saving: Call `EncryptedSecretService.encryptAndStore()` to create/update EncryptedSecret
  - Store returned secret ID in `environmentVariablesSecretId` field
  - When loading: Call `EncryptedSecretService.retrieveAndDecrypt()` to get plaintext
  - When deleting: Call `EncryptedSecretService.deleteSecret()` to clean up (if not referenced elsewhere)

**Implementation Details:**
- ✅ Server DAO at `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/LocalMCPServerDaoExposed.kt`
- ✅ Client DAO at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/database/dao/LocalMCPServerLocalDaoImpl.kt`
- ✅ Comprehensive tests for both server and client DAOs

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

#### US1.3 - Local MCP Tool Definition Linkage & DAO ✅ **COMPLETED**
**As a** system
**I want** to link MCP tools to their source servers
**So that** I can track which server provides which tools

**Status**: ✅ **FULLY IMPLEMENTED**

**Database Schema Acceptance Criteria:**
- [x] Create `LocalMCPToolDefinitionTable` junction table:
  - `toolDefinitionId` (FK to `ToolDefinitionTable`, PK, CASCADE on delete)
  - `mcpServerId` (FK to `LocalMCPServerTable`, CASCADE on delete)
  - `mcpToolName` (String, nullable) - **FUTURE**: original tool name from MCP server (for name mapping)
  - `isEnabledByDefault` (Boolean, nullable) - per-tool default enablement override
  - Primary key on `toolDefinitionId` (one-to-one relationship with tool)
- [x] Update `ToolDefinitionTable` to support MCP tools:
  - Remove unique constraint on `name` field (tool names are NOT globally unique)
  - Support `type = ToolType.MCP_LOCAL` for MCP tools
  - **Note**: This is separate from `ToolDefinition.isEnabled` which globally enables/disables the tool

**DAO Acceptance Criteria:**
- [x] Create `LocalMCPToolDefinitionDao` interface with entity-based methods:
  - `createToolEntity(toolDefinitionId, mcpServerId, mcpToolName?, isEnabledByDefault?)` - creates linkage
    - Returns `Either<CreateLinkageError, Unit>`
  - `getToolEntityById(toolDefinitionId)` - retrieves linkage by tool ID
    - Returns `Either<LocalMCPToolDefinitionError.NotFound, LocalMCPToolDefinitionEntity>`
  - `getToolEntitiesByServerId(mcpServerId)` - retrieves all linkages for a server
    - Returns `List<LocalMCPToolDefinitionEntity>`
  - `deleteToolEntityById(toolDefinitionId)` - deletes linkage (for explicit unlinking during refresh)
    - Returns `Either<LocalMCPToolDefinitionError.NotFound, Unit>`
  - `deleteToolEntitiesByServerId(mcpServerId)` - deletes all linkages for a server
    - Returns `Int` (count of deleted linkages)
- [x] Create `LocalMCPToolDefinitionEntity` data class:
  - `toolDefinitionId: Long`
  - `mcpServerId: Long`
  - `mcpToolName: String?`
  - `isEnabledByDefault: Boolean?`
- [x] Implement Exposed-based DAO following existing patterns
- [x] Add cascade delete behavior (automatically handled by FK constraints)

**Implementation Details:**
- ✅ Table at `server/src/main/kotlin/eu/torvian/chatbot/server/data/tables/LocalMCPToolDefinitionTable.kt`
- ✅ DAO at `server/src/main/kotlin/eu/torvian/chatbot/server/data/dao/exposed/LocalMCPToolDefinitionDaoExposed.kt`
- ✅ Comprehensive tests at `server/src/test/kotlin/eu/torvian/chatbot/server/data/dao/exposed/LocalMCPToolDefinitionDaoExposedTest.kt`

**Technical Notes:**
- **Entity-based approach**: DAO works with `LocalMCPToolDefinitionEntity` (linkage metadata only)
- **Separation of concerns**: 
  - `ToolDefinitionDao` manages the actual tool definitions
  - `LocalMCPToolDefinitionDao` manages the linkage/junction table
  - Service layer (US1.3B) coordinates between the two DAOs
- **One-to-one relationship**: Each tool definition has at most one MCP server linkage (PK on toolDefinitionId)
- **Cascade deletes**: When MCP server or tool is deleted, linkages are automatically removed
- **Explicit unlinking**: `deleteToolEntityById()` allows manual unlinking during refresh operations
- **FUTURE - Name Mapping**: The `mcpToolName` field enables optional mapping from LLM tool name to MCP server tool name
  - Field added to schema but feature not implemented in initial release
  - If `mcpToolName` is null, the tool definition name is used as-is
  - If `mcpToolName` is set, it maps: `ToolDefinition.name` (used by LLM) → `mcpToolName` (used by MCP server)
  - This allows users to simplify long/complex MCP tool names for LLM usage
- **Tool Name Uniqueness**: Tool names must be unique within enabled tools for a given chat session
  - This is enforced at the application level, not database level
  - Users are responsible for ensuring unique names within their account
  - The app should warn users if duplicate names are detected in enabled tools
- **Default enablement hierarchy**:
  - Tool-level `isEnabledByDefault` (in junction table) overrides server-level default
  - If null, falls back to `LocalMCPServerLocalTable.toolsEnabledByDefault`
  - If that's also null, default is disabled

---

#### US1.3A - Local MCP Tool Definition Model (Common Module) ✅ **COMPLETED**
**As a** developer
**I want** a dedicated model class for MCP tools
**So that** MCP-specific fields are strongly typed and the domain model is clear

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Convert `ToolDefinition` to a sealed class in common module:
  - Move existing fields to abstract properties
  - Add abstract method `withUpdatedAt(newUpdatedAt: Instant): ToolDefinition`
- [x] Create `LocalMCPToolDefinition` data class extending `ToolDefinition`:
  - `serverId: Long` - unique identifier of the MCP server providing this tool
  - `mcpToolName: String?` - optional original tool name from MCP server (for name mapping)
  - `isEnabledByDefault: Boolean?` - per-tool default enablement for new sessions
  - `type: ToolType` - fixed to `ToolType.MCP_LOCAL` (override val)
  - All standard ToolDefinition fields (id, name, description, config, schemas, etc.)
  - Implement `withUpdatedAt()` using copy()
- [x] Create `MiscToolDefinition` data class for non-MCP tools:
  - All standard ToolDefinition fields
  - Implement `withUpdatedAt()` using copy()
- [x] Update serialization to handle sealed class:
  - Add `@Serializable` to sealed class and subclasses
  - Ensure proper polymorphic JSON serialization

**Implementation Details:**
- ✅ Sealed class at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/tool/ToolDefinition.kt`
- ✅ LocalMCPToolDefinition at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/tool/LocalMCPToolDefinition.kt`
- ✅ MiscToolDefinition at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/tool/MiscToolDefinition.kt`

**Technical Notes:**
- **Sealed class benefits**:
  - Compile-time exhaustive when() checking
  - Type-safe access to MCP-specific fields
  - Clear domain model separation
  - Prevents invalid tool type combinations
- **serverId embedded in model**: Hydrated from junction table but exposed as model property
- **Type is fixed**: `LocalMCPToolDefinition.type` always returns `ToolType.MCP_LOCAL`
- **Common module location**: Available to both server and client code
- **Backwards compatibility**: Migration needed for existing tool definitions
- **Name mapping**: `mcpToolName` field enables optional LLM name → MCP server name mapping (future feature)
- **Default enablement**: `isEnabledByDefault` controls whether tool is enabled by default for NEW sessions
  - Hierarchy: Tool-level override → Server-level default → Disabled

**API Impact:**
- [x] Update DTOs to handle polymorphic ToolDefinition:
  - Request/response DTOs should use sealed class discriminator
  - JSON serialization handles subtype differentiation
- [x] Add dedicated endpoints for LocalMCPToolDefinition operations:
  - `POST /api/v1/local-mcp-tools/batch` - batch create with serverId in body
  - `GET /api/v1/local-mcp-tools/server/{serverId}` - get all tools for server
  - `GET /api/v1/local-mcp-tools/{toolId}` - get single MCP tool
  - `PUT /api/v1/local-mcp-tools/{toolId}` - update MCP tool
  - `DELETE /api/v1/local-mcp-tools/server/{serverId}` - delete all tools for server

---

#### US1.3B - Local MCP Tool Definition Service Layer (Server-Side) ✅ **COMPLETED**
**As a** backend developer
**I want** a service layer to coordinate MCP tool creation and linkage management
**So that** tools and their server linkages are created atomically

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `LocalMCPToolDefinitionService` interface with methods:
  - `createMCPTools(tools)` - batch creates tools with linkages atomically
    - Parameter: `tools: List<LocalMCPToolDefinition>`
    - Creates ToolDefinition entries via ToolDefinitionDao
    - Creates linkage entries via LocalMCPToolDefinitionDao
    - Returns `Either<LocalMCPToolDefinitionServiceError, List<LocalMCPToolDefinition>>`
    - **Atomicity**: Single database transaction for all operations
  - `getMCPToolsByServerId(serverId)` - retrieves all tools for a server
    - Fetches tool entities via LocalMCPToolDefinitionDao
    - Hydrates full LocalMCPToolDefinition objects via ToolDefinitionDao
    - Returns `Either<LocalMCPToolDefinitionServiceError, List<LocalMCPToolDefinition>>`
  - `getMCPToolById(toolId)` - retrieves single MCP tool
    - Fetches tool entity and tool definition
    - Hydrates LocalMCPToolDefinition object
    - Returns `Either<LocalMCPToolDefinitionServiceError, LocalMCPToolDefinition>`
  - `updateMCPTool(tool)` - updates MCP tool and linkage
    - Updates ToolDefinition via ToolDefinitionDao
    - Updates linkage if serverId or metadata changed
    - Returns `Either<LocalMCPToolDefinitionServiceError, LocalMCPToolDefinition>`
  - `deleteMCPToolsForServer(serverId)` - deletes all tools for a server
    - Deletes all tool definitions for the server
    - Linkages cascade-deleted automatically
    - Returns `Either<LocalMCPToolDefinitionServiceError, Int>` (count)
  - `refreshMCPTools(serverId, currentTools)` - differential tool refresh
    - Compares current tools with existing tools
    - Adds new, updates changed, deletes removed
    - Returns `Either<LocalMCPToolDefinitionServiceError, RefreshResult>`
    - `RefreshResult` contains: `added: Int, updated: Int, deleted: Int`
- [x] Implement service with transaction management:
  - Use database transactions for atomic operations
  - Coordinate between ToolDefinitionDao and LocalMCPToolDefinitionDao
  - Handle errors from both DAOs
- [x] Create `LocalMCPToolDefinitionServiceError` sealed class hierarchy
- [x] Add validation for business rules:
  - Validate serverId exists (via LocalMCPServerDao)
  - Validate tool names are unique within server's tools

**Implementation Details:**
- ✅ Service interface at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/LocalMCPToolDefinitionService.kt`
- ✅ Implementation at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/LocalMCPToolDefinitionServiceImpl.kt`
- ✅ Comprehensive tests at `server/src/test/kotlin/eu/torvian/chatbot/server/service/core/impl/LocalMCPToolDefinitionServiceImplTest.kt`
  - Validate input schemas are valid JSON Schema

**Dependencies:**
- `ToolDefinitionDao` - for creating/updating/deleting tool definitions
- `LocalMCPToolDefinitionDao` - for managing linkages
- `LocalMCPServerDao` - for validating server existence

**Technical Notes:**
- **Coordination layer**: Service coordinates between two DAOs to maintain consistency
- **Atomicity guarantee**: All operations within a method are wrapped in a single transaction
- **Hydration pattern**: Service hydrates full LocalMCPToolDefinition from:
  - ToolDefinition (from ToolDefinitionDao)
  - LocalMCPToolDefinitionEntity (from LocalMCPToolDefinitionDao)
  - Combines the two to create LocalMCPToolDefinition with embedded serverId
- **Error handling**: Maps DAO errors to service-level errors with business context
- **Validation**: Enforces business rules that span multiple DAOs
- **Batch operations**: Optimizes bulk creates with batch inserts
- **Refresh logic**: Differential update algorithm:
  1. Get existing tools via `getMCPToolsByServerId()`
  2. Compare with current tools by name
  3. Identify: new tools (not in existing), changed tools (different schema/description), removed tools (not in current)
  4. Execute: create new, update changed, delete removed
  5. Return summary of changes

**Example Transaction Flow (createMCPTools):**
```
BEGIN TRANSACTION
  FOR EACH tool in tools:
    1. toolId = toolDefinitionDao.insertToolDefinition(...)
    2. localMCPToolDefinitionDao.createToolEntity(toolId, serverId, ...)
COMMIT (or ROLLBACK on error)
```

---

#### US1.4 - Local MCP Server Service Layer (Server-Side - ID Generation Only) ✅ **COMPLETED**
**As a** backend developer
**I want** a service layer for local MCP server ID generation
**So that** clients can obtain unique IDs for their local configurations

**Note**: This is separate from `LocalMCPToolDefinitionService` (US1.3B) which handles tool management.

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `LocalMCPServerService` interface with methods:
  - `generateServerId(userId)` - creates new entry, returns server-generated ID
  - `deleteServer(serverId)` - deletes entry (validates ownership, cascades to tool linkages)
  - `getServerIdsByUser(userId)` - lists all server IDs owned by user
  - `validateOwnership(userId, serverId)` - checks if user owns server
- [x] Implement service with minimal business logic
- [x] Add validation for ownership

**Implementation Details:**
- ✅ Service interface at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/LocalMCPServerService.kt`
- ✅ Implementation at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/LocalMCPServerServiceImpl.kt`
- ✅ Comprehensive tests at `server/src/test/kotlin/eu/torvian/chatbot/server/service/core/impl/LocalMCPServerServiceImplTest.kt`

**Technical Notes:**
- Server only generates IDs and tracks ownership
- No configuration validation (that's client-side)
- Authorization checks handled at route layer
- Use server ID as primary identifier for operations
- Deletion cascades to LocalMCPToolDefinitionTable (removes linkages)

---

### Phase 2: MCP Client Management (Desktop Client)

#### US2.1 - Local MCP Server Process Manager ✅ **COMPLETED**
**As a** desktop application
**I want** to manage local MCP server processes
**So that** I can start/stop local MCP servers on demand

**Status**: ✅ **FULLY IMPLEMENTED** (Desktop only, Android/WASM stubs)

**Acceptance Criteria:**
- [x] Create `LocalMCPServerProcessManager` interface (KMP common) with:
  - `startServer(config: LocalMCPServer)` - launches process with STDIO, returns ProcessStatus
  - `stopServer(serverId)` - gracefully terminates process with forceful fallback
  - `getServerStatus(serverId)` - returns current ProcessStatus
  - `restartServer(config)` - stops and starts server
  - `stopAllServers()` - cleanup during application shutdown
  - Stream access methods: `getProcessInputStream()`, `getProcessOutputStream()`, `getProcessErrorStream()`
- [x] Implement `LocalMCPServerProcessManagerDesktop` (JVM platform) with:
  - Thread-safe process tracking using `ConcurrentHashMap<Long, ManagedProcess>`
  - Configuration validation (command, arguments)
  - Environment variable setup and working directory configuration
  - Optimistic process startup with atomic registration (CAS loop for race condition handling)
  - Graceful shutdown with configurable timeout, followed by force-kill if needed
  - Process status tracking (RUNNING, STOPPED, ERROR states)
- [x] Implement comprehensive error handling via Arrow Either:
  - `StartServerError` (ProcessAlreadyRunning, InvalidConfiguration, ProcessStartFailed, etc.)
  - `StopServerError`, `RestartServerError` sealed classes
- [x] Manage STDIO streams using `kotlinx.io` (Source/Sink abstraction)

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPServerProcessManager.kt`
- ✅ Desktop implementation at `app/src/desktopMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPServerProcessManagerDesktop.kt`
- ✅ Android stub at `app/src/androidMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPProcessManagerAndroid.kt` (TODO)
- ✅ Comprehensive tests at `app/src/desktopTest/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPServerProcessManagerTest.kt`

**Technical Notes:**
- **Platform-independent interface** in `commonMain` enables future platform implementations
- **Desktop implementation** uses Java `ProcessBuilder` API
- **Thread-safe process tracking** via `ConcurrentHashMap` for high concurrency scenarios
- **Pure process management** - no MCP SDK knowledge, config passed as parameter
- **Stateless design** - operations receive config as parameter, no internal state beyond process map
- **Graceful shutdown** - attempts graceful stop first (5s timeout), then force-kills (2s timeout)
- **Resource cleanup** - proper stream handling and process cleanup on shutdown
- Called by `MCPClientService` (US2.3) for process lifecycle operations

---

#### US2.2 - Local MCP Server Manager (High-Level Orchestration) ✅ **COMPLETED**
**As a** desktop application
**I want** a manager service to orchestrate MCP server workflows
**So that** I can coordinate between data, MCP operations, and API persistence

**Status**: ✅ **FULLY IMPLEMENTED** (Desktop/Android, WASM stub)

**Acceptance Criteria:**
- [x] Create `LocalMCPServerManager` interface (KMP common) with:
  - `testConnectionForNewServer(...)` - tests connection to a new MCP server configuration
    - Creates temporary server config
    - Calls MCPClientService to start and connect
    - Discovers tools and returns count
    - Cleans up (stops server)
  - `createServer(...)` - creates and persists a new MCP server with tools
    - Creates temporary config
    - Starts and connects via MCPClientService
    - Discovers tools via MCPClientService
    - Persists server config via LocalMCPServerRepository
    - Persists tools via LocalMCPToolRepository
    - Cleans up (stops server)
    - Returns created server
  - `testConnection(serverId)` - tests connection to an existing MCP server
    - Gets config from repository
    - Starts and connects (if not already connected)
    - Discovers tools and returns count
    - Cleans up (stops server if started by this operation)
  - `refreshTools(serverId)` - orchestrates tool refresh
    - Gets config from LocalMCPServerRepository
    - Starts and connects (if not already connected)
    - Discovers current tools via MCPClientService
    - Converts MCP Tool objects to LocalMCPToolDefinition format
    - Calls LocalMCPToolRepository.refreshMCPTools() for differential sync
    - Cleans up (stops server if started by this operation)
    - Returns refresh summary (added/updated/deleted counts)
  - `startServer(serverId)` - starts an MCP server and connects client
  - `stopServer(serverId)` - stops an MCP server and disconnects client
  - `callTool(serverId, toolName, arguments)` - executes a tool on an MCP server
    - Ensures server is started and connected
    - Calls MCPClientService.callTool()
    - Returns CallToolResultBase or error
- [x] Implement `LocalMCPServerManagerImpl` (commonMain) with:
  - Reactive `serverOverviews` StateFlow (combines servers, clients, and tools)
  - Comprehensive error handling via Arrow Either
  - Detailed logging for all operations
  - Automatic cleanup (stops servers after operations complete)
  - Data transformation (MCP SDK Tool → LocalMCPToolDefinition)
- [x] Inject dependencies:
  - `LocalMCPServerRepository` - for reading/writing cached MCP server configs
  - `LocalMCPToolRepository` - for persisting discovered MCP tools (US6.1A)
  - `MCPClientService` - for MCP operations (US2.3)
  - `Clock` - for generating timestamps (injectable for testing)
  - `CoroutineScope` - for managing background operations
- [x] Handle errors from all layers with typed error hierarchies
- [x] Provide detailed error messages for UI

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPServerManager.kt`
- ✅ Implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPServerManagerImpl.kt`
- ✅ Error types at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPServerManagerError.kt`
- ✅ KMP common implementation (platform-independent orchestration logic)

**Technical Notes:**
- **High-level orchestration layer** between UI and MCP operations
- Coordinates data flow: LocalMCPServerRepository (configs) → MCPClientService (MCP ops) → LocalMCPToolRepository (tool persistence)
- Handles data transformation (MCP SDK Tool → LocalMCPToolDefinition)
- **Reactive aggregate state**: `serverOverviews` combines data from 3 sources:
  - LocalMCPServerRepository.servers (server configurations)
  - MCPClientService.clients (active client connections)
  - LocalMCPToolRepository.mcpTools (tool definitions by server)
- Does not manage state directly (delegates to repositories)
- Does not manage processes (delegates to MCPClientService)
- Does not call API directly (delegates to repositories)
- Pure business logic and workflow coordination
- Called by ViewModels for user-initiated actions
- **Automatic resource cleanup**: Always stops servers after test/create/refresh operations
- **Smart connection management**: Only starts servers if needed, only stops if started by the operation
- **Separation of concerns**:
  - LocalMCPServerRepository for MCP server data
  - LocalMCPToolRepository for MCP tool data (US6.1A)
  - ToolRepository for non-MCP tool data (unchanged)

---

#### US2.3 - MCP Client Service (MCP Operations Layer) ✅ **COMPLETED**
**As a** desktop application
**I want** a service to handle MCP-specific operations
**So that** I can manage MCP server processes and SDK interactions without coupling to data layer

**Status**: ✅ **FULLY IMPLEMENTED** (Desktop/Android, WASM stub)

**Acceptance Criteria:**
- [x] Create `MCPClientService` interface (KMP common) with:
  - **Lifecycle operations:**
    - `startAndConnect(config)` - starts process and establishes MCP SDK client connection
      - Checks if already connected (returns AlreadyConnected error if so)
      - Starts process via LocalMCPServerProcessManager
      - Gets process I/O streams for STDIO transport
      - Creates MCP SDK Client with unique name per server
      - Connects SDK client to server process
      - Stores client in internal map
      - Starts auto-stop timer if configured
      - Returns comprehensive error on failure
    - `stopServer(serverId)` - disconnects SDK client and stops process
      - Cancels auto-stop timer
      - Removes client from internal map
      - Disconnects MCP SDK client
      - Stops process via LocalMCPServerProcessManager
    - `disconnectAll()` - cleanup all clients during shutdown
    - `close()` - cleanup and cancel service scope
  - **Tool operations:**
    - `discoverTools(serverId)` - lists available tools from MCP server via SDK
      - Calls MCP SDK `listTools()` RPC
      - Updates last activity timestamp
      - Resets auto-stop timer
      - Returns List<Tool>
    - `callTool(serverId, toolName, arguments)` - executes tool and returns raw SDK result
      - Calls MCP SDK `callTool()` RPC
      - Updates last activity timestamp
      - Resets auto-stop timer
      - Returns CallToolResultBase or error
  - **Status & health:**
    - `getServerStatus(serverId)` - returns current ProcessStatus from ProcessManager
    - `isClientRegistered(serverId)` - quick synchronous check if client exists in map
    - `pingClient(serverId)` - performs lightweight health-check via SDK ping()
      - Returns false immediately if no client exists
      - Performs I/O ping via SDK
      - Updates lastPing status in client data
  - **Client enumeration:**
    - `getClient(serverId)` - returns MCPClient snapshot or null
    - `listClients()` - returns list of active MCPClient objects with status
  - **Reactive state:**
    - `clients: StateFlow<Map<Long, MCPClient>>` - reactive stream of all active clients
      - Automatically updates when client state changes
      - Enables UI to react to connection state changes
- [x] Implement `MCPClientServiceImpl` (commonMain - platform-agnostic) with:
  - Thread-safe client management using `MutableStateFlow<Map<Long, MCPClientInternal>>`
  - Integration with `LocalMCPServerProcessManager` for process lifecycle
  - MCP SDK `Client` creation with STDIO transport using process streams
  - Client metadata tracking:
    - `serverConfig` - full LocalMCPServer configuration
    - `processStatus` - current process status
    - `sdkClient` - MCP SDK Client instance
    - `connectedAt` - connection timestamp
    - `lastActivityAt` - timestamp of last operation (tool call/discovery)
    - `lastPing` - result of last health check
    - `autoStopTimerJob` - coroutine job for auto-stop timer
  - Unique client naming per server: `chatbot-mcp-client-{sanitized-name}-{id}`
    - Sanitizes server name (lowercase, alphanumeric + hyphens/underscores only)
    - Maximum 64 characters total
  - **Auto-stop timer management:**
    - Starts timer after successful connection
    - Resets timer on every tool operation (callTool, discoverTools)
    - Cancels timer on manual stop
    - Uses server's `effectiveAutoStopSeconds` configuration
    - Respects `neverAutoStop` flag (no timer if true)
  - **Activity tracking:**
    - Updates `lastActivityAt` on every MCP operation
    - Resets auto-stop timer when activity occurs
    - Enables inactivity-based auto-stop
- [x] Implement comprehensive error handling via Arrow Either:
  - `StartAndConnectError` sealed class hierarchy:
    - `AlreadyConnected(serverId)` - client already exists
    - `ProcessStartFailed(serverId, reason, cause)` - process failed to start
    - `StreamsUnavailable(serverId, reason)` - I/O streams not available
    - `SDKConnectionFailed(serverId, reason, cause)` - SDK connection failed
  - `MCPStopServerError` sealed class hierarchy:
    - `DisconnectFailed(serverId, reason, cause)` - SDK disconnect failed
    - `ProcessStopFailed(serverId, reason, cause)` - process stop failed
  - `DiscoverToolsError` sealed class hierarchy:
    - `NotConnected(serverId)` - no active client
    - `SDKListToolsFailed(serverId, reason, cause)` - SDK RPC failed
  - `CallToolError` sealed class hierarchy:
    - `NotConnected(serverId, toolName)` - no active client
    - `SDKCallToolFailed(serverId, toolName, reason, cause)` - SDK RPC failed
- [x] Handle MCP SDK communication errors and edge cases
- [x] WASM stub implementation (returns "not implemented" errors)

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/MCPClientService.kt`
- ✅ Implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/MCPClientServiceImpl.kt` (commonMain - not platform-specific)
- ✅ Error types at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/MCPClientServiceError.kt`
- ✅ WASM stub at `app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/service/mcp/MCPClientServiceWasm.kt`
- ✅ Comprehensive tests at `app/src/desktopTest/kotlin/eu/torvian/chatbot/app/service/mcp/MCPClientServiceTest.kt`

**Technical Notes:**
- **Platform-independent interface** in `commonMain` for KMP compatibility
- **Implementation in commonMain** (not platform-specific) - relies on LocalMCPServerProcessManager for platform differences
- **Desktop/Android support** via LocalMCPServerProcessManager implementation
- **Pure MCP operations layer** - no Repository/API dependencies, config passed as parameter
- **Thread-safe client management** via `MutableStateFlow` for concurrent operations
- **Stateless design** except for active client connections (stored in memory StateFlow)
- **Reactive architecture** - StateFlow enables UI to observe connection state changes
- **Automatic cleanup** - disconnects SDK clients and stops processes on stopServer()
- **Wraps MCP Kotlin SDK** for easier testing, abstraction, and error handling
- **Client lifecycle tracking** - stores connection time, activity time, ping status, and server config
- **Auto-stop timer management**:
  - Prevents resource waste by stopping idle servers
  - Configurable timeout per server
  - Automatic reset on activity
  - Graceful shutdown with cleanup
- Called by `LocalMCPServerManager` (US2.2) for high-level orchestration workflows
- **WASM unavailable** - requires process management capabilities (no Node.js child_process in browser)
- **MCP SDK Integration**:
  - Uses official MCP Kotlin SDK
  - STDIO transport for local process communication
  - Standard MCP RPC protocol (listTools, callTool, ping)
  - Proper client identification with unique names

---

#### US2.4 - Local MCP Server Connection Testing UI ❌ **NOT IMPLEMENTED**
**As a** user
**I want** to test my local MCP server connection
**So that** I can verify it's configured correctly

**Status**: ❌ **NOT IMPLEMENTED** - UI missing (orchestration layer complete)

**Acceptance Criteria:**
- [ ] Add "Test Connection" button to MCP server management UI
- [ ] Implement connection test flow:
  - User clicks button
  - ViewModel calls `localMCPServerManager.testConnection(serverId)` (✅ method exists)
  - Show loading indicator
  - Display success/failure result
  - Show discovered tool count on success
  - Show detailed error message on failure
- [ ] Add timeout handling (30 seconds)
- [ ] Provide user-friendly error messages

**Implementation Notes:**
- ✅ **AVAILABLE**: LocalMCPServerManager.testConnection(serverId) (US2.2) fully implemented
- ✅ **AVAILABLE**: LocalMCPServerManager.testConnectionForNewServer(...) for testing new configs
- ⚠️ **MISSING**: MCP server management UI not implemented
- ✅ **READY**: Backend orchestration is complete, only UI work remains

**Technical Notes:**
- Connection test is orchestrated by LocalMCPServerManager (US2.2) ✅ **IMPLEMENTED**
- LocalMCPServerManager delegates MCP operations to MCPClientService (US2.3) ✅ **IMPLEMENTED**
- Don't persist tools during test (that's a separate "Discover Tools" action) ✅ **CORRECTLY IMPLEMENTED**
- LocalMCPServerManager provides typed error results for UI error messages
- Test is idempotent (can be run multiple times safely)
- This is a client-side operation (no server API endpoint needed)
- Auto-cleanup: Server is stopped after test if started by the test operation

---

### Phase 3: Tool Discovery & Synchronization

#### US3.1 - Local MCP Tool Discovery and Persistence ⚠️ **PARTIALLY IMPLEMENTED**
**As a** system
**I want** to persist discovered tools from local MCP servers
**So that** they can be used by the LLM

**Status**: ⚠️ **PARTIALLY IMPLEMENTED** - Backend and orchestration complete, UI missing

**Acceptance Criteria:**
- [x] Create server-side tool persistence logic:
  - ✅ `createMCPTools(serverId, toolDefinitions)` - batch creates tools with linkages
  - ✅ **Atomic transaction**: Creates ToolDefinition entries AND LocalMCPToolDefinitionTable linkages in single transaction
  - ✅ Ensures consistency (no partial failures - either all succeed or all rollback)
  - ✅ Sets `type = ToolType.MCP_LOCAL` for all created tools
  - ✅ Returns created tool definitions with IDs
- [x] Convert MCP `Tool` objects to `LocalMCPToolDefinition` entities:
  - ✅ LocalMCPServerManager.createServer() converts MCP SDK Tool → LocalMCPToolDefinition
  - ✅ Conversion includes: name, description, inputSchema, outputSchema, serverId
  - ✅ Sets timestamps (createdAt, updatedAt) using Clock
  - ✅ Sets default values for new tools
- [x] Handle tool name conflicts (validation)
- [x] Add proper error handling for duplicate tools
- [ ] UI for tool discovery **NOT IMPLEMENTED**

**Implementation Details:**
- ✅ Server service at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/LocalMCPToolDefinitionServiceImpl.kt`
- ✅ API routes at `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureLocalMCPServerRoutes.kt`
- ✅ Client orchestration at `LocalMCPServerManagerImpl.createServer()` method
- ✅ LocalMCPToolRepository.persistMCPTools() handles client-side API call
- ✅ MCPClientService.discoverTools() available for listing tools from MCP server
- ❌ UI not implemented (no discover tools button in management screen)

**Technical Notes:**
- **Client-side orchestration** in `LocalMCPServerManager.createServer()` (US2.2) ✅ **IMPLEMENTED**:
  - Calls MCPClientService (US2.3) to list tools from running MCP server
  - Converts MCP SDK Tool objects to LocalMCPToolDefinition format
  - Calls LocalMCPToolRepository.persistMCPTools()
  - LocalMCPToolRepository calls LocalMCPToolApi endpoint to persist tools
  - Returns created server with tools persisted
- **Server-side persistence** (this user story) ✅ **IMPLEMENTED**:
  - Server validates and stores tool definitions
  - Server creates BOTH ToolDefinition AND LocalMCPToolDefinitionTable in **atomic transaction**
  - Server returns created tools to client
  - **Atomicity ensures**: Either all tools persist successfully, or none do (rollback)
- **Repository coordination** (client-side) ✅ **IMPLEMENTED**:
  - LocalMCPToolRepository updates cache after successful API call
  - LocalMCPServerRepository doesn't need notification (it doesn't cache tools)
- Tools are discovered on client during server creation, persisted on server
- Discovery happens automatically during server creation (LocalMCPServerManager.createServer)

---

#### US3.2 - Tool Refresh Mechanism ⚠️ **PARTIALLY IMPLEMENTED**
**As a** user
**I want** to refresh the tool list from an MCP server
**So that** I can get updated tools when the server changes

**Status**: ⚠️ **PARTIALLY IMPLEMENTED** - Backend and orchestration complete, UI missing

**Acceptance Criteria:**
- [ ] Add "Refresh Tools" button to MCP server management UI **NOT IMPLEMENTED**
- [x] Implement refresh logic in `LocalMCPServerManager.refreshTools(serverId)`: **FULLY IMPLEMENTED**
  - ✅ LocalMCPServerManager.refreshTools() orchestrates complete workflow
  - ✅ Gets config from LocalMCPServerRepository
  - ✅ Starts and connects to server (if not already connected)
  - ✅ Discovers current tools via MCPClientService.discoverTools()
  - ✅ Converts MCP SDK Tool objects to LocalMCPToolDefinition format
  - ✅ Calls LocalMCPToolRepository.refreshMCPTools() for differential sync
  - ✅ Backend service compares tool lists (by name)
  - ✅ Identifies: new tools, changed tools (schema), deleted tools
  - ✅ Persists changes atomically
  - ✅ Returns RefreshMCPToolsResponse with counts (added/updated/deleted)
  - ✅ Stops server after refresh (if started by this operation)
- [ ] Preserve user's per-session tool enablement settings **NOT IMPLEMENTED**
- [x] Handle tool schema changes gracefully
- [ ] Show summary of changes to user (X added, Y updated, Z removed) **NOT IMPLEMENTED** (backend returns this, UI doesn't exist)
- [ ] Emit events for UI updates **PARTIALLY IMPLEMENTED** (StateFlow updates automatically, no explicit events)

**Implementation Details:**
- ✅ Server service method at `LocalMCPToolDefinitionServiceImpl.refreshMCPTools()`
- ✅ API endpoint at `POST /api/v1/local-mcp-servers/{serverId}/tools/refresh`
- ✅ Client orchestration at `LocalMCPServerManagerImpl.refreshTools()`
- ✅ LocalMCPToolRepository.refreshMCPTools() handles client-side API call
- ✅ MCPClientService.discoverTools() fetches current tools from MCP server
- ❌ UI not implemented (no refresh tools button in management screen)

**Technical Notes:**
- Use tool name as the comparison key ✅ **IMPLEMENTED**
- Don't delete tools that are currently enabled in active sessions (mark as deprecated instead) ❌ **NOT IMPLEMENTED**
- Log all changes for audit trail ✅ **IMPLEMENTED** (in LocalMCPServerManager)
- Refresh is orchestrated by LocalMCPServerManager (US2.2) ✅ **IMPLEMENTED**
- Server provides batch update API endpoint ✅ **IMPLEMENTED**
- Auto-cleanup: Server is stopped after refresh if started by the refresh operation ✅ **IMPLEMENTED**
- Smart connection management: Only starts if needed, only stops if started by this operation ✅ **IMPLEMENTED**

---

### Phase 4: MCP Tool Execution

**Note**: This phase was implemented differently than originally planned. See US7.3 for the actual implementation using LocalMCPExecutor and LocalMCPToolCallMediator.

#### US4.1 - Local MCP Tool Executor ✅ **COMPLETED** (Implemented as LocalMCPExecutor)
**As a** system
**I want** an executor for local MCP tools
**So that** the LLM can invoke them during conversations

**Status**: ✅ **IMPLEMENTED** (as LocalMCPExecutor, not as ToolExecutor implementation)

**Acceptance Criteria:**
- [x] Create executor for local MCP tools (implemented as `LocalMCPExecutor`)
- [x] Implement tool execution that:
  - ✅ Emits `MessageStreamEvent.LocalMCPToolCallReceived` via WebSocket
  - ✅ Waits for tool result from client
  - ✅ Returns tool result or error
- [x] Integrate with ChatService for `ToolType.MCP_LOCAL`
- [x] Handle execution timeouts (60 seconds)

**Implementation Details:**
- ✅ Implemented at `server/src/main/kotlin/eu/torvian/chatbot/server/service/mcp/LocalMCPExecutor.kt`
- ✅ Integrated into ChatService at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/ChatServiceImpl.kt`
- ⚠️ **DIFFERENT APPROACH**: Not implemented as ToolExecutor interface, but as dedicated service called by ChatService

**Technical Notes:**
- Local MCP tools execute on the client, not the server
- Tool execution request is sent via WebSocket (MessageStreamEvent.LocalMCPToolCallReceived)
- Server waits for result using Flow-based event collection
- Uses `withTimeoutOrNull` for timeout handling (60s)
- Proper error handling for client disconnection and timeouts

---

#### US4.2 - WebSocket Message Processing Integration ✅ **COMPLETED**
**As a** system
**I want** to use WebSocket for bidirectional communication
**So that** MCP tool execution is possible

**Status**: ✅ **IMPLEMENTED** (WebSocket already in use for chat streaming)

**Acceptance Criteria:**
- [x] WebSocket route for message processing (already exists)
- [x] Add new message types for MCP tool execution:
  - ✅ `MessageStreamEvent.LocalMCPToolCallReceived` (server → client)
  - ✅ `LocalMCPToolCallResult` (client → server)
- [x] Create tool result data structures:
  - ✅ `LocalMCPToolCallRequest` (toolCallId, serverId, toolName, inputJson)
  - ✅ `LocalMCPToolCallResult` (toolCallId, isError, content, errorMessage)
- [x] WebSocket handles bidirectional communication:
  - ✅ Server sends tool execution requests
  - ✅ Client sends tool results
  - ✅ Connection lifecycle management
- [x] ChatService integration:
  - ✅ Emits LocalMCPToolCallReceived events when MCP tools need execution
  - ✅ Waits for results from client
  - ✅ Continues message processing with tool results

**Implementation Details:**
- ✅ WebSocket route at `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureSessionRoutes.kt`
- ✅ Message types at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/tool/LocalMCPToolCallRequest.kt` and `LocalMCPToolCallResult.kt`
- ✅ ChatService integration at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/ChatServiceImpl.kt`

**Technical Notes:**
- WebSocket frames use JSON serialization for all messages
- Client sends LocalMCPToolCallResult objects as JSON
- Server sends MessageStreamEvent objects as JSON
- Proper cleanup when WebSocket connection closes
- Timeout handling (60s per tool call)
- Session ID validation and authorization checks

---

#### US4.3 - Client-Side Local MCP Tool Execution Handler ✅ **COMPLETED** (Implemented as LocalMCPToolCallMediator)
**As a** desktop client
**I want** to execute local MCP tools when requested by the server
**So that** the LLM can use local tools

**Status**: ✅ **IMPLEMENTED** (as LocalMCPToolCallMediator)

**Acceptance Criteria:**
- [x] Create tool execution handler (implemented as `LocalMCPToolCallMediator`):
  - ✅ Receives LocalMCPToolCallReceived events via WebSocket
  - ✅ Looks up the appropriate MCP client connection
  - ✅ Calls the tool using `MCPClientService.callTool()`
  - ✅ Sends LocalMCPToolCallResult back to server via WebSocket
- [x] Handle execution errors gracefully
- [x] Implement timeout handling
- [x] Log all tool executions

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPToolCallMediator.kt`
- ✅ Desktop/Android implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPToolCallMediatorImpl.kt`
- ✅ WASM dummy at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPToolCallMediatorDummy.kt`

**Technical Notes:**
- Listens for WebSocket frames and deserializes to MessageStreamEvent
- Filters for LocalMCPToolCallReceived event type
- Executes tools asynchronously
- Validates tool arguments (JSON parsing)
- Checks that the local MCP server is running before execution
- Converts MCP tool results to LocalMCPToolCallResult format
- Sends LocalMCPToolCallResult as JSON via WebSocket frame
- Handles concurrent tool executions

---

### Phase 5: API & Routes

#### US5.1 - Local MCP Server API Routes (ID Generation) ✅ **COMPLETED**
**As a** frontend developer
**I want** REST API endpoints for local MCP server ID generation
**So that** clients can obtain unique IDs for their local configurations

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `configureLocalMCPServerRoutes.kt` with minimal endpoints:
  - `POST /api/v1/mcp-servers/generate-id` - generate new server ID (returns ID only)
  - `GET /api/v1/mcp-servers/ids` - list all server IDs for user
  - `DELETE /api/v1/mcp-servers/{id}` - delete server ID (validates ownership, cascades to tool linkages)
- [x] Add authorization checks (user must own server for delete)
- [x] Implement minimal request/response DTOs (only ID and userId)

**Implementation Details:**
- ✅ Routes at `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureLocalMCPServerRoutes.kt`
- ✅ Resource models at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/api/resources/LocalMCPServerResource.kt`
- ✅ Comprehensive tests at `server/src/test/kotlin/eu/torvian/chatbot/server/ktor/routes/LocalMCPServerRoutesTest.kt`

**Technical Notes:**
- Server does NOT store or validate configuration
- Only generates IDs and tracks ownership
- Client stores full configuration locally
- Follow existing route patterns
- Use `authenticate(AuthSchemes.USER_JWT)`
- Return appropriate HTTP status codes
- Include error handling

---

#### US5.2 - Local MCP Tool Management API Routes ✅ **COMPLETED**
**As a** frontend developer
**I want** REST API endpoints for local MCP tool management
**So that** users can manage tools from their local MCP servers

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Add dedicated endpoints for `LocalMCPToolDefinition` operations:
  - ✅ `POST /api/v1/local-mcp-tools/batch` - batch create LocalMCPToolDefinition
    - Request: `CreateMCPToolsRequest { serverId: Long, tools: List<LocalMCPToolDefinition> }`
    - Response: `CreateMCPToolsResponse { tools: List<LocalMCPToolDefinition> }` with server-generated IDs
    - Creates both ToolDefinition and LocalMCPToolDefinitionTable entries atomically
    - Returns HTTP 201 Created on success
  - ✅ `POST /api/v1/local-mcp-tools/refresh` - refresh tools for server (differential update)
    - Request: `RefreshMCPToolsRequest { serverId: Long, currentTools: List<LocalMCPToolDefinition> }`
    - Response: `RefreshMCPToolsResponse { added: Int, updated: Int, deleted: Int }`
    - Compares current tools with existing, adds/updates/deletes as needed
    - Returns summary of changes
  - ✅ `GET /api/v1/local-mcp-tools/server/{serverId}` - get all tools for a specific MCP server
    - Returns `List<LocalMCPToolDefinition>` with serverId hydrated from junction table
  - ✅ `GET /api/v1/local-mcp-tools/{toolId}` - get single MCP tool by ID
    - Returns `LocalMCPToolDefinition` with serverId
  - ✅ `PUT /api/v1/local-mcp-tools/{toolId}` - update MCP tool
    - Request body: `LocalMCPToolDefinition`
    - Validates tool ID in path matches body
    - serverId cannot be changed
  - ✅ `DELETE /api/v1/local-mcp-tools/server/{serverId}` - delete all tools for a server
    - Response: `DeleteMCPToolsResponse { count: Int }`
    - Deletes both ToolDefinition entries and linkages
    - Returns count of deleted tools
- [x] Add authorization checks for all endpoints
  - ✅ Validate user owns the MCP server (via serverId)
  - ✅ Use existing authorization patterns (JWT authentication)
- [x] Validate tool name uniqueness within user's enabled tools
- [x] Return appropriate HTTP status codes (201 for create, 200 for success, 404 for not found, etc.)
- [x] Implement comprehensive error handling

**Implementation Details:**
- ✅ Routes at `server/src/main/kotlin/eu/torvian/chatbot/server/ktor/routes/configureLocalMCPToolRoutes.kt`
- ✅ Resources at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/api/resources/LocalMCPToolResource.kt`
- ✅ Request/Response DTOs at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/api/mcp/LocalMCPToolRequests.kt`
- ✅ Service at `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/LocalMCPToolDefinitionServiceImpl.kt`
- ✅ Comprehensive tests at `server/src/test/kotlin/eu/torvian/chatbot/server/ktor/routes/LocalMCPToolRoutesTest.kt`

**Technical Notes:**
- **Base path**: `/api/v1/local-mcp-tools` (NOT nested under `/api/v1/local-mcp-servers` as originally planned)
- **Model-specific**: All endpoints work with `LocalMCPToolDefinition` model
- **Refresh endpoint**: Added as a convenience endpoint for differential updates (not in original plan)
- **Polymorphic serialization**: Handles ToolDefinition sealed class with LocalMCPToolDefinition subtype
- **Atomic operations**: All create/update/delete operations are wrapped in database transactions
- Tool discovery, refresh, and connection testing are client-side operations (not API routes)
  - These operations happen on the desktop client where the MCP server runs (via MCPClientService)
  - No server-side API endpoints needed for these operations
- **Batch creation endpoint** is critical for tool discovery:
  - LocalMCPServerManager discovers tools locally (via MCPClientService)
  - Converts them to LocalMCPToolDefinition format
  - Calls LocalMCPToolRepository.persistMCPTools() (US6.1A)
  - LocalMCPToolRepository calls LocalMCPToolApi.createMCPToolsForServer() (US6.2)
  - LocalMCPToolApi calls POST /api/v1/local-mcp-tools/batch
  - **Server creates BOTH ToolDefinitions and LocalMCPToolDefinitionTable linkages atomically**
  - Single transaction ensures consistency (no partial failures)
  - Returns created tools with IDs
- **serverId is embedded in LocalMCPToolDefinition**: Request/response DTOs include serverId
- Creating a tool involves both `ToolDefinition` and `LocalMCPToolDefinitionTable` entries
- The name mapping endpoint (FUTURE) updates the `mcpToolName` field in the junction table
- **These endpoints are consumed by LocalMCPToolApi** (US6.2), not by ToolApi
- **Polymorphic serialization**: Server must handle ToolDefinition sealed class properly

---

### Phase 6: Frontend Integration

#### US6.1 - Local MCP Server Repository (Client-Side Local Storage) ✅ **COMPLETED**
**As a** frontend developer
**I want** a repository for local MCP server data with local storage
**So that** ViewModels can access local MCP server state across platforms

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `LocalMCPServerRepository` interface with:
  - `val servers: StateFlow<DataState<List<LocalMCPServer>>>`
  - `loadServers()` - fetches from local SQLDelight database
  - `createServer(server)` - requests ID from API, stores full config locally
  - `updateServer(server)` - updates local SQLDelight database
  - `deleteServer(serverId)` - deletes from local DB and calls API to delete ID
  - `getServerById(serverId)` - retrieves single server by ID
- [x] Implement repository with caching
- [x] Handle API errors and map to `RepositoryError`
- [x] Emit state updates for reactive UI

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/LocalMCPServerRepository.kt`
- ✅ Implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/impl/DefaultLocalMCPServerRepository.kt`
- ✅ API client at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/api/LocalMCPServerApi.kt`
- ✅ Comprehensive tests at `app/src/desktopTest/kotlin/eu/torvian/chatbot/app/repository/LocalMCPServerRepositoryTest.kt`

**Technical Notes:**
- Follow patterns from `SessionRepository`, `ToolRepository`
- Cache server list in StateFlow
- Invalidate cache on mutations
- **Scope**: Only manages LocalMCPServer data (not tools)
- **Tool persistence**: Handled by LocalMCPToolRepository (US6.1A - separate repository - NOT YET IMPLEMENTED)
- **Note**: `testConnection()`, `discoverTools()`, and `refreshTools()` are NOT repository methods
  - These operations are orchestrated by `LocalMCPServerManager` (US2.2 - NOT YET IMPLEMENTED)
  - LocalMCPServerManager reads MCP server configs from this repository
  - LocalMCPServerManager calls LocalMCPToolRepository for tool persistence
  - Separation of concerns:
    - LocalMCPServerRepository = MCP server state management
    - LocalMCPToolRepository = MCP tool state management (US6.1A - NOT YET IMPLEMENTED)
    - ToolRepository = non-MCP tool state management (unchanged)
    - LocalMCPServerManager = orchestration between repositories (NOT YET IMPLEMENTED)

---

#### US6.1A - Local MCP Tool Repository (NEW - Separate from ToolRepository) ✅ **COMPLETED**
**As a** frontend developer
**I want** a dedicated repository for MCP tools
**So that** MCP tools can be managed separately from non-MCP tools with clear separation of concerns

**Status**: ✅ **FULLY IMPLEMENTED** - Dedicated repository created with map-based caching

**Acceptance Criteria:**
- [x] Create new `LocalMCPToolRepository` interface with MCP-specific methods:
  - `val mcpTools: StateFlow<DataState<RepositoryError, Map<Long, List<LocalMCPToolDefinition>>>>>`
    - Map structure: serverId → List of LocalMCPToolDefinition
    - Enables efficient lookup by server without filtering
  - `loadMCPTools()` - loads all MCP tools for current user
  - `persistMCPTools(serverId, toolDefinitions)` - persist discovered MCP tools with linkages
    - Parameter: `List<LocalMCPToolDefinition>` (with serverId embedded)
    - Calls LocalMCPToolApi batch endpoint to create tools and linkages atomically
    - Invalidates cache and updates StateFlow
    - Returns `List<LocalMCPToolDefinition>` with server-generated IDs
  - `getToolsByServerId(serverId)` - get all tools for a specific MCP server
    - Returns `List<LocalMCPToolDefinition>` with serverId
    - Returns cached tools if available from map
  - `refreshMCPTools(serverId, currentToolDefinitions)` - update tools for MCP server
    - Parameter: `List<LocalMCPToolDefinition>` representing current state
    - Compares with existing tools (add new, update changed, remove deleted)
    - Calls API to persist changes
    - Returns `RefreshMCPToolsResponse` with (added, updated, deleted) counts
  - `deleteMCPToolsForServer(serverId)` - delete all tools for a server
    - Removes all LocalMCPToolDefinition entries for serverId
    - Returns count of deleted tools
  - `getMCPToolById(toolId)` - get single MCP tool by ID
    - Returns `LocalMCPToolDefinition`
- [x] Implement repository with independent caching
- [x] Handle API errors and map to `RepositoryError`
- [x] Maintain cache consistency
- [x] Use LocalMCPToolApi for all server communication

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/LocalMCPToolRepository.kt`
- ✅ Implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/repository/impl/DefaultLocalMCPToolRepository.kt`
- ✅ Registered in Koin DI module at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/koin/appModule.kt`
- ✅ Backend endpoint added: `GET /api/v1/local-mcp-tools` to get all MCP tools for current user
- ✅ Backend DAO method: `getToolsForUser(userId)` in LocalMCPToolDefinitionDao
- ✅ Backend service method: `getMCPToolsForUser(userId)` in LocalMCPToolDefinitionService

**Technical Notes:**
- **New repository** (not an extension of ToolRepository)
- **Works with LocalMCPToolDefinition model** (not generic ToolDefinition)
- Follow existing patterns from `SessionRepository`, `ToolRepository`
- **Map-based cache**: `Map<Long, List<LocalMCPToolDefinition>>` for efficient server-based lookups
- **API batch endpoint**: `POST /api/v1/local-mcp-tools/batch`
  - Server creates BOTH ToolDefinitions AND LocalMCPToolDefinitionTable linkages atomically
  - Single transaction ensures consistency
  - Request/response uses LocalMCPToolDefinition model with embedded serverId
- **Cache strategy**: Invalidate entire MCP tool cache when any MCP tools change
  - Map structure simplifies partial invalidation (only update affected serverId entry)
  - Does not affect ToolRepository's cache (separate caching)
- **No dependency on ToolRepository or LocalMCPServerRepository**
- Called by LocalMCPServerManager (US2.2) for tool persistence
- **Benefits of separation**:
  - Clear ownership: LocalMCPToolRepository owns MCP tools, ToolRepository owns non-MCP tools
  - Type-safe: Works with LocalMCPToolDefinition, not generic ToolDefinition
  - Independent evolution: Can add MCP-specific features without affecting ToolRepository
  - Better testability: Mock only what you need
  - Reduced coupling: Changes to MCP tools don't affect non-MCP tool logic

---

#### US6.2 - Local MCP Tool API Client (NEW - Separate from ToolApi) ✅ **COMPLETED**
**As a** frontend developer
**I want** a dedicated API client for MCP tool operations
**So that** LocalMCPToolRepository can communicate with MCP-specific backend endpoints

**Status**: ✅ **FULLY IMPLEMENTED** - Dedicated API client created with comprehensive tests

**Acceptance Criteria (Based on Actual Backend Endpoints):**
- [x] Create new `LocalMCPToolApi` interface with dedicated MCP endpoints:
  - `createMCPToolsForServer(serverId: Long, tools: List<LocalMCPToolDefinition>)` - batch create LocalMCPToolDefinition tools
    - Calls `POST /api/v1/local-mcp-tools/batch`
    - Returns: `List<LocalMCPToolDefinition>` with server-generated IDs
  - `refreshMCPToolsForServer(serverId: Long, currentTools: List<LocalMCPToolDefinition>)` - differential tool refresh
    - Calls `POST /api/v1/local-mcp-tools/refresh`
    - Returns: `RefreshMCPToolsResponse { addedTools: List<LocalMCPToolDefinition>, updatedTools: List<LocalMCPToolDefinition>, deletedTools: List<LocalMCPToolDefinition> }`
  - `getMCPToolsForServer(serverId: Long)` - get all tools for a server
    - Calls `GET /api/v1/local-mcp-tools/server/{serverId}`
    - Returns: `List<LocalMCPToolDefinition>` with serverId hydrated
  - `getMCPToolById(toolId: Long)` - get single MCP tool
    - Calls `GET /api/v1/local-mcp-tools/{toolId}`
    - Returns: `LocalMCPToolDefinition?`
  - `updateMCPTool(tool: LocalMCPToolDefinition)` - update MCP tool
    - Calls `PUT /api/v1/local-mcp-tools/{toolId}`
    - Request: `LocalMCPToolDefinition`
    - Returns: Updated `LocalMCPToolDefinition`
  - `deleteMCPToolsForServer(serverId: Long)` - delete all tools for a server
    - Calls `DELETE /api/v1/local-mcp-tools/server/{serverId}`
    - Returns: `Int` (count of deleted tools)
- [x] Implement `KtorLocalMCPToolApiClient` using Ktor
- [x] Map HTTP errors to `ApiResourceError`
- [x] Follow Ktor client configuration patterns
- [x] Handle polymorphic ToolDefinition serialization (sealed class)
- [x] Comprehensive test coverage

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/api/LocalMCPToolApi.kt`
- ✅ Implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/api/ktor/KtorLocalMCPToolApiClient.kt`
- ✅ Registered in Koin DI module at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/koin/appModule.kt`
- ✅ Comprehensive tests at `app/src/desktopTest/kotlin/eu/torvian/chatbot/app/service/api/ktor/KtorLocalMCPToolApiClientTest.kt`
  - Tests for all endpoints (create, refresh, get, update, delete)
  - Success and failure scenarios
  - Serialization error handling
  - HTTP error mapping

**Technical Notes:**
- **New API client** (not an extension of ToolApi)
- **Works with LocalMCPToolDefinition model** throughout
- **Actual endpoint paths**: `/api/v1/local-mcp-tools/...` (confirmed from implementation)
  - `POST /api/v1/local-mcp-tools/batch` - batch create
  - `POST /api/v1/local-mcp-tools/refresh` - differential refresh
  - `GET /api/v1/local-mcp-tools/server/{serverId}` - get by server
  - `GET /api/v1/local-mcp-tools/{toolId}` - get by ID
  - `PUT /api/v1/local-mcp-tools/{toolId}` - update
  - `DELETE /api/v1/local-mcp-tools/server/{serverId}` - delete all for server
- MCP tools have specific batch operations and server-based grouping
- Follow patterns from `SessionApi`, `ToolApi`, `LocalMCPServerApi`
- Use existing Ktor client configuration
- **Polymorphic serialization**: Handles ToolDefinition sealed class with LocalMCPToolDefinition subtype
- **Benefits of separation**:
  - Clear API boundaries between MCP and non-MCP tools
  - Type-safe LocalMCPToolDefinition usage
  - Independent versioning and evolution
  - Easier to document and understand
  - No risk of breaking existing ToolApi functionality
  - Can add MCP-specific features without affecting ToolApi

---

#### US6.3 - Local MCP Server API Client ✅ **COMPLETED**
**As a** frontend developer
**I want** an API client for local MCP server endpoints
**So that** LocalMCPServerRepository can communicate with the backend

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `LocalMCPServerApi` interface
- [x] Implement API client using Ktor
- [x] Add methods for MCP server CRUD endpoints (no tool endpoints)
- [x] Map HTTP errors to `ApiResourceError`

**Implementation Details:**
- ✅ Interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/api/LocalMCPServerApi.kt`
- ✅ Implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/api/impl/KtorLocalMCPServerApiClient.kt`
- ✅ Comprehensive tests at `app/src/desktopTest/kotlin/eu/torvian/chatbot/app/service/api/LocalMCPServerApiTest.kt`

**Technical Notes:**
- Uses existing Ktor client configuration
- Follows patterns from `SessionApi`, `ToolApi`
- **Does NOT include tool endpoints** (those would be in LocalMCPToolApi - US6.2 - not implemented)
- Only handles LocalMCPServer ID generation and deletion

---

#### US6.4 - Local MCP Server Management UI ✅ **COMPLETED** (2025-12-10)
**As a** user
**I want** a UI to manage my local MCP servers
**So that** I can configure which tools are available

**Status**: ✅ **COMPLETED** - Full management UI implemented

**Acceptance Criteria:**
- [x] Create `LocalMCPServerManagementScreen` composable with:
  - List of configured local MCP servers
  - Add/Edit/Delete server dialogs
  - Test connection button with status indicator
  - Discover/Refresh tools buttons
  - Enable/Disable server toggle
- [x] Create `LocalMCPServerViewModel` for state management
- [x] Show server status (running/stopped/error)
- [x] Display tool count for each server

**Implementation Notes:**
- ✅ **IMPLEMENTED**: LocalMCPServerViewModel for state management
- ✅ **IMPLEMENTED**: LocalMCPServersTab with master-detail layout
- ✅ **IMPLEMENTED**: LocalMCPServersListPanel showing all servers
- ✅ **IMPLEMENTED**: LocalMCPServerDetailPanel with comprehensive server info
- ✅ **IMPLEMENTED**: Test Connection, Refresh Tools, Start/Stop Server actions
- ✅ **IMPLEMENTED**: Real-time status updates via LocalMCPServerOverview
- ✅ **IMPLEMENTED**: Tool count display in list view
- ✅ **IMPLEMENTED**: Full tool list with descriptions in detail view
- ✅ **IMPLEMENTED**: Operation-in-progress indicators
- ✅ **IMPLEMENTED**: Desktop-only (registered in desktopModule)

**Technical Notes:**
- Uses Material 3 components throughout
- Follows Route pattern (LocalMCPServersTabRoute)
- Integrated as 4th tab in SettingsScreen
- State management via StateFlow
- Error handling via ErrorNotifier
- Clean separation: ViewModel → Repository → Manager → Service

**Files Created:**
- `LocalMCPServerViewModel.kt` - State management and business logic
- `LocalMCPServerState.kt` - UI state and actions interfaces
- `LocalMCPServersTabRoute.kt` - Route component
- `LocalMCPServersTab.kt` - Main tab with master-detail layout
- `LocalMCPServersListPanel.kt` - Server list with badges and status
- `LocalMCPServerDetailPanel.kt` - Server details with actions and tool list

---

#### US6.5 - Local MCP Server Configuration Dialog ✅ **COMPLETED** (2025-12-10)
**As a** user
**I want** a dialog to configure local MCP server details
**So that** I can set up new servers or edit existing ones

**Status**: ✅ **COMPLETED** - Comprehensive configuration dialog implemented

**Acceptance Criteria:**
- [x] Create dialog with fields:
  - Name (required)
  - Description (optional)
  - Command (required, with file picker)
  - Arguments (list of strings, add/remove)
  - Environment Variables (key-value pairs, add/remove)
  - Working Directory (optional, with directory picker)
  - Auto-start options (On Enable, On Launch)
  - Auto-stop timeout (nullable integer)
  - Tools enabled by default (nullable boolean)
- [x] Validate all inputs before submission
- [x] Show helpful error messages
- [x] Support both create and edit modes

**Implementation Notes:**
- ✅ **IMPLEMENTED**: LocalMCPServerConfigDialog with all required fields
- ✅ **IMPLEMENTED**: LocalMCPServerFormState for form state management
- ✅ **IMPLEMENTED**: Dynamic ArgumentsSection with add/remove functionality
- ✅ **IMPLEMENTED**: Dynamic EnvironmentVariablesSection with key-value pairs
- ✅ **IMPLEMENTED**: Form validation (name and command required)
- ✅ **IMPLEMENTED**: Loading state during save operations
- ✅ **IMPLEMENTED**: Delete confirmation dialog
- ✅ **IMPLEMENTED**: Scrollable content for long forms
- ✅ **IMPLEMENTED**: Error handling via ErrorNotifier
- ⚠️ **NOTE**: File/directory pickers not implemented (manual path entry)

**Technical Notes:**
- Uses Material 3 OutlinedTextField components
- Dynamic lists for arguments and environment variables
- Supporting text and placeholders for guidance
- Environment variables noted as encrypted
- Supports both Add New and Edit modes
- Form state includes all LocalMCPServer properties
- Validates required fields before submission

**Files Created:**
- `LocalMCPServerDialogs.kt` - Configuration dialog and delete confirmation
  - LocalMCPServerConfigDialog - Main configuration form
  - ArgumentsSection - Dynamic argument list management
  - EnvironmentVariablesSection - Dynamic env var key-value pairs
  - Delete confirmation with warning message

---

### Phase 7: Session Tool Configuration

#### US7.1 - Local MCP Tool Session Configuration ✅ **COMPLETED**
**As a** user
**I want** to enable/disable local MCP tools per session
**So that** I can control which tools are available in each conversation

**Status**: ✅ **FULLY IMPLEMENTED** (2025-12-11)

**Acceptance Criteria:**
- [x] Extend existing tool configuration dialog to show local MCP tools
- [x] Group tools by local MCP server in the UI
- [x] Disable tool toggle if server is globally disabled (`LocalMCPServer.isEnabled = false`)
- [x] Disable tool toggle if tool is globally disabled (`ToolDefinition.isEnabled = false`)
- [x] Update `SessionToolConfigTable` for MCP tools (already supported through existing ToolRepository)
- [ ] (optional) Warn user if duplicate tool names are detected in enabled tools

**Implementation Details:**
- ✅ Updated `ToolConfigPanel.kt` with server-grouped tool display
  - Collapsible sections for each MCP server and built-in tools
- ✅ Updated `ChatAreaDialogState.ToolConfig` to include `mcpServersFlow` parameter
- ✅ Updated `ChatViewModel` to inject `LocalMCPServerRepository` and pass server data to dialog
- ✅ Updated `LoadSessionUseCase` to load MCP servers when loading a session
  - Added `LocalMCPServerRepository` and `AuthRepository` dependencies
  - Parallel loading of MCP servers alongside other session data
- ✅ Updated Koin module with all new dependencies
- ✅ Session-level tool configuration uses existing `ToolRepository.setToolEnabledForSession()` method
  - Works seamlessly for both MCP and non-MCP tools
  - Updates `SessionToolConfigTable` on server-side

**Technical Notes:**
- Reuses existing `ToolRepository` for session-level tool management (no separate MCP logic needed)
- Server grouping logic implemented with collapsible sections for better UX
- MCP servers loaded when session loads to ensure fresh data
- **Global vs Session Enable/Disable**:
  - Global disable (`isEnabled = false`) prevents tool from being enabled in ANY session
  - Session enable/disable allows per-conversation customization when globally enabled
  - Users can manually enable/disable tools per session regardless of default settings

---

#### US7.2 - Local MCP Server Auto-Start ❌ **NOT IMPLEMENTED**
**As a** user
**I want** local MCP servers to auto-start when needed
**So that** I don't have to manually start them

**Status**: ❌ **NOT IMPLEMENTED** - Auto-start logic not implemented

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

#### US7.3 - Local MCP Tool Execution via WebSocket ✅ **COMPLETED**
**As a** system
**I want** to execute local MCP tools via WebSocket communication between server and client
**So that** the LLM can use tools from local MCP servers running on the user's machine

**Status**: ✅ **FULLY IMPLEMENTED**

**Acceptance Criteria:**
- [x] Create `LocalMCPExecutor` service (server-side) that:
  - Sends tool execution requests to client via WebSocket
  - Waits for tool execution results from client
  - Handles timeouts (60 seconds)
  - Emits execution events (request, result, error)
- [x] Create `LocalMCPToolCallMediator` interface (client-side) that:
  - Receives tool call requests from server via WebSocket
  - Executes tools using MCPClientService
  - Returns results to server via WebSocket
- [x] Implement `LocalMCPToolCallMediatorImpl` (Desktop/Android) with:
  - JSON parsing for tool arguments
  - Tool execution via MCPClientService.callTool()
  - Error handling for invalid JSON and execution failures
  - Result formatting for server consumption
- [x] Implement `LocalMCPToolCallMediatorDummy` (WASM/other platforms) as placeholder
- [x] Integrate with ChatService for tool execution loop:
  - Detect LocalMCPToolDefinition tools
  - Route to LocalMCPExecutor instead of standard ToolExecutor
  - Emit LocalMCPToolCallReceived events for WebSocket transmission
  - Handle tool execution results from client
- [x] Create common models for WebSocket communication:
  - `LocalMCPToolCallRequest` (toolCallId, serverId, toolName, inputJson)
  - `LocalMCPToolCallResult` (toolCallId, isError, content, errorMessage)
- [x] Add WebSocket message types for MCP tool execution:
  - `MessageStreamEvent.LocalMCPToolCallReceived` (server → client)
  - Client sends `LocalMCPToolCallResult` back to server

**Implementation Details:**
- ✅ Server executor at `server/src/main/kotlin/eu/torvian/chatbot/server/service/mcp/LocalMCPExecutor.kt`
- ✅ Client mediator interface at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPToolCallMediator.kt`
- ✅ Desktop/Android implementation at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPToolCallMediatorImpl.kt`
- ✅ WASM dummy at `app/src/commonMain/kotlin/eu/torvian/chatbot/app/service/mcp/LocalMCPToolCallMediatorDummy.kt`
- ✅ Common models at `common/src/commonMain/kotlin/eu/torvian/chatbot/common/models/tool/LocalMCPToolCallRequest.kt` and `LocalMCPToolCallResult.kt`
- ✅ Integration in `server/src/main/kotlin/eu/torvian/chatbot/server/service/core/impl/ChatServiceImpl.kt`

**Technical Notes:**
- **WebSocket-based execution**: Server sends requests to client, client executes locally and returns results
- **Timeout handling**: 60-second timeout for tool execution to prevent hanging
- **Platform-specific**: Desktop/Android have full implementation, WASM has dummy (no local process support)
- **Separation of concerns**:
  - LocalMCPExecutor (server) - orchestrates execution flow, doesn't know about MCP SDK
  - LocalMCPToolCallMediator (client) - handles actual tool execution via MCPClientService
  - MCPClientService - manages MCP SDK client connections and tool calls
- **Error handling**: Handles JSON parsing errors, execution errors, timeouts, and missing results
- **Integration with chat flow**: Seamlessly integrated into existing tool execution loop in ChatService
- **Event-driven**: Uses Flow-based events for asynchronous communication

---

### Phase 8: Advanced Features

#### US8.1 - Local MCP Tool Default Enablement for New Sessions ⚠️ **PARTIALLY IMPLEMENTED**
**As a** user
**I want** to set default enablement for MCP tools in new chat sessions
**So that** new sessions automatically have my preferred tools enabled

**Status**: ⚠️ **PARTIALLY IMPLEMENTED** - Schema fields exist, logic not implemented

**Acceptance Criteria:**
- [ ] Implement two-level default enablement for NEW chat sessions: **PARTIALLY IMPLEMENTED**
  1. **Server-level default**: `LocalMCPServerTable.toolsEnabledByDefault` (Boolean, nullable)
     - ✅ Field exists in schema
     - ❌ Logic not implemented
  2. **Tool-level override**: `ToolDefinitionTable.isEnabledByDefault` (Boolean, nullable)
     - ✅ Field exists in schema
     - ❌ Logic not implemented
- [ ] Create UI to configure default enablement at both levels **NOT IMPLEMENTED**
- [ ] Apply defaults when creating new sessions: **NOT IMPLEMENTED**
  - Check tool-level `isEnabledByDefault` first
  - If null, fall back to server-level `toolsEnabledByDefault`
  - If server-level is also null, default to disabled
  - **Important**: Only apply if tool is globally enabled (`ToolDefinition.isEnabled = true` AND `LocalMCPServer.isEnabled = true`)
- [ ] Allow per-tool override in session configuration (users can manually enable/disable at any time) **NOT IMPLEMENTED**

**Implementation Notes:**
- ✅ **AVAILABLE**: Schema fields exist in LocalMCPServerLocalTable and ToolDefinitionTable
- ❌ **MISSING**: Session creation logic to apply defaults
- ❌ **MISSING**: UI to configure defaults

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

#### US8.2 - Local MCP Server Health Monitoring ❌ **NOT IMPLEMENTED**
**As a** user
**I want** to see the health status of my local MCP servers
**So that** I know if they're working correctly

**Status**: ❌ **NOT IMPLEMENTED** - No health monitoring logic

**Acceptance Criteria:**
- [ ] Implement periodic health checks (every 30s)
- [ ] Check server process status
- [ ] Ping local MCP server via `listTools()` call
- [ ] Show status indicators in UI (green/yellow/red)
- [ ] Log health check failures

**Implementation Notes:**
- ✅ **AVAILABLE**: MCPClientService.pingClient() can be used for health checks
- ✅ **AVAILABLE**: LocalMCPServerProcessManager.getServerStatus() for process status
- ❌ **MISSING**: Periodic health check logic
- ❌ **MISSING**: UI status indicators

**Technical Notes:**
- Run health checks in background coroutine
- Don't spam server with requests
- Cache health status for UI
- Allow manual health check trigger

---

#### US8.3 - Local MCP Tool Execution Logs ❌ **NOT IMPLEMENTED**
**As a** user
**I want** to view logs of local MCP tool executions
**So that** I can debug issues and understand what tools are doing

**Status**: ❌ **NOT IMPLEMENTED** - No logging or log viewer

**Acceptance Criteria:**
- [ ] Add logging to `LocalMCPExecutor`
- [ ] Create log viewer UI component
- [ ] Show:
  - Timestamp, tool name, server name
  - Input arguments, output result
  - Execution duration, status (success/error)
- [ ] Filter logs by server, tool, status
- [ ] Export logs to file

**Implementation Notes:**
- ⚠️ **PARTIAL**: LocalMCPExecutor likely has some logging via standard logger
- ❌ **MISSING**: Structured execution logs
- ❌ **MISSING**: Log viewer UI
- ❌ **MISSING**: Log export functionality

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
- MCP tool linkage tables and DAO (US1.3)
- LocalMCPToolDefinition model class (US1.3A)
- LocalMCPToolDefinitionService for coordinating tool creation (US1.3B)
- Server service layer for ID generation (US1.4 - optional)

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

### Phase 6: Frontend (US6.1 - US6.5) ✅ **COMPLETED**
**Goal:** UI for local MCP server management
**Duration:** 1-2 weeks
**Dependencies:** Phase 5

**Key Deliverables:**
- ✅ LocalMCPServerRepository for MCP server state management (US6.1)
- ✅ **LocalMCPToolRepository for MCP tool persistence (US6.1A) - NEW separate repository**
- ✅ **LocalMCPToolApi for MCP tool endpoints (US6.2) - NEW separate API client**
- ✅ LocalMCPServerApi for MCP server CRUD (US6.3)
- ✅ **Management UI and configuration dialogs (US6.4, US6.5) - COMPLETED 2025-12-10**

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

## Implementation Status Summary (as of 2025-12-10)

### What's Been Implemented ✅

**Backend Infrastructure (100% Complete)**
- ✅ Encrypted secret storage with envelope encryption (US1.0)
- ✅ Server-side minimal storage (LocalMCPServerTable with id and userId) (US1.1)
- ✅ Client-side full storage (SQLDelight with LocalMCPServerLocalTable) (US1.2)
- ✅ Local MCP tool linkage tables and DAOs (US1.3)
- ✅ LocalMCPToolDefinition sealed class model (US1.3A)
- ✅ LocalMCPToolDefinitionService for coordinated tool operations (US1.3B)
- ✅ LocalMCPServerService for ID generation (US1.4)
- ✅ API routes for server ID generation and tool management (US5.1, US5.2)

**Client Infrastructure (100% Complete)**
- ✅ LocalMCPServerProcessManager for process lifecycle (Desktop only) (US2.1)
- ✅ LocalMCPServerManager orchestration layer (Desktop/Android) (US2.2)
- ✅ MCPClientService for MCP SDK operations (Desktop/Android) (US2.3)
- ✅ LocalMCPServerRepository for local storage (US6.1)
- ✅ LocalMCPToolRepository for MCP tool state (US6.1A)
- ✅ LocalMCPToolApi for MCP tool backend communication (US6.2)
- ✅ LocalMCPServerApi for backend communication (US6.3)

**Tool Execution (100% Complete)**
- ✅ LocalMCPExecutor for server-side tool execution orchestration (US7.3)
- ✅ LocalMCPToolCallMediator for client-side tool execution (US7.3)
- ✅ WebSocket-based bidirectional communication (US4.2, US7.3)
- ✅ Integration with ChatService for tool calling loop (US4.1, US4.3)

**User Interface (100% Complete)** ✅ **NEWLY COMPLETED (2025-12-10)**
- ✅ Local MCP Server Management UI (US6.4) ✅ **NEW**
  - Master-detail layout with server list and detail panel
  - Real-time status indicators (Enabled/Disabled, Connected/Stopped/Operating)
  - Tool count display in list view
  - Full tool list with descriptions in detail view
  - Action buttons (Test Connection, Refresh Tools, Start/Stop, Enable/Disable)
- ✅ Local MCP Server Configuration Dialog (US6.5) ✅ **NEW**
  - Comprehensive form with all server configuration fields
  - Dynamic arguments and environment variables management
  - Form validation and error handling
  - Support for both create and edit modes

### What's Missing ❌

**UI Workflows**
- ❌ Connection Testing UI (US2.4) - backend ready, UI button exists, could add more visual feedback
- ❌ Tool Discovery/Refresh UI (US3.1, US3.2) - backend ready, UI buttons exist, could add more visual feedback

**Session & Tool Configuration**
- ❌ Session-level tool configuration UI (US7.1)
- ❌ Auto-start logic implementation (US7.2)
- ❌ Default enablement logic (US8.1 - schema exists, logic missing)

**Advanced Features**
- ❌ Health monitoring (US8.2)
- ❌ Execution logs and log viewer (US8.3)

### Key Architectural Decisions Made During Implementation

1. **WebSocket Tool Execution (NEW)**: Implemented LocalMCPExecutor and LocalMCPToolCallMediator for WebSocket-based tool execution instead of the originally planned ToolExecutor interface approach. This provides better separation of concerns and cleaner integration with the chat flow.

2. **Dedicated MCP Tool API Client**: Created LocalMCPToolApi as a separate API client for MCP-specific tool operations, distinct from the general ToolApi. This provides clear separation of concerns and type-safe handling of LocalMCPToolDefinition.

3. **Dedicated MCP Tool Repository**: Implemented LocalMCPToolRepository as a separate repository for MCP tools with map-based caching (serverId → List<LocalMCPToolDefinition>) for efficient server-based lookups. This maintains clean separation from ToolRepository which handles non-MCP tools.

4. **Complete Orchestration Layer (NEW)**: LocalMCPServerManager was fully implemented with:
   - Reactive aggregate state (serverOverviews StateFlow)
   - Complete workflow orchestration (test, create, refresh, start/stop)
   - Smart connection management (only start/stop when needed)
   - Automatic resource cleanup
   - Comprehensive error handling

5. **Auto-stop Timer Management (NEW)**: MCPClientService implements sophisticated auto-stop functionality:
   - Automatic timer reset on tool activity
   - Configurable timeout per server
   - Respects neverAutoStop flag
   - Graceful cleanup on timer expiration

### Remaining Work Estimate

**High Priority (Core Functionality)**
- ✅ ~~Management UI (US6.4, US6.5) - 2 weeks~~ **COMPLETED 2025-12-10**
- Tool discovery/refresh workflows UI (US3.1, US3.2) - 1 week (backend complete, buttons exist, needs enhanced feedback)
- Connection testing UI (US2.4) - 3 days (backend complete, button exists, needs enhanced feedback)

**Medium Priority (Enhanced UX)**
- Session-level tool configuration (US7.1) - 1 week
- Auto-start implementation (US7.2) - 1 week
- Default enablement logic (US8.1) - 3 days

**Low Priority (Polish)**
- Health monitoring (US8.2) - 3 days
- Execution logs (US8.3) - 1 week

**Total Remaining Estimate**: 3-4 weeks (down from 5-6 weeks)

**Total Remaining Effort**: ~5-7 weeks (reduced from 6-8 weeks due to US2.2 completion)

---

## References

- [MCP Kotlin SDK Reference](../docs/MCP/Kotlin-SDK/Reference.md)
- [Current Tool Architecture](../server/src/main/kotlin/eu/torvian/chatbot/server/service/tool/)
- [Ownership Patterns](../server/src/main/kotlin/eu/torvian/chatbot/server/data/tables/)
