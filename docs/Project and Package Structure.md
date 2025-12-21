# Project and Package Structure

## Overview

This is a multi-module Kotlin desktop application for AI chatbot interactions, built using Compose Multiplatform, Ktor, and Exposed ORM.  
**The application now uses a client-server architecture by default:**
- The backend (Ktor server) runs as a separate process, exposing APIs for the frontend (Compose desktop app) to consume.
- An integrated monolith (single-process UI+backend) will be implemented as an option in a future release.

## Technology Stack

- **Language**: Kotlin 2.2.0
- **UI Framework**: Compose Multiplatform 1.8.2 with Material 3
- **HTTP Server/Client**: Ktor 3.2.3 (Server & HTTP Client)
- **Database ORM**: Exposed 0.61.0
- **Dependency Injection**: Koin 4.1.0
- **Logging**: Log4j 2.25.1
- **Functional Programming**: Arrow 2.1.2
- **Date/Time**: kotlinx.datetime
- **Database**: SQLite (local persistence)
- **Build**: Gradle

## Module Structure

### Root Project Structure
```
chatbot/
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Module definitions
├── gradle.properties             # Global Gradle properties
├── app/                          # Frontend logic (API clients, ViewModels, UI)
├── build-logic/                  # Custom Gradle convention plugins
│   └── src/main/kotlin/
│       └── CommonModuleConventionPlugin.kt
├── common/                       # Shared models and utilities
├── docs/                         # Project documentation
├── gradle/
│   ├── libs.versions.toml        # Version catalog
│   └── wrapper/                  # Gradle wrapper
└── server/                       # Backend logic and services
```

## Detailed Module Breakdown

### 1. Common Module (`common/`)

**Purpose**: Shared data models (DTOs) and utilities used by both frontend and backend modules.

**Package Structure**:
```
common/src/commonMain/kotlin/eu/torvian/chatbot/common/
├── api/resources/                # API resource definitions (for Ktor Resources plugin)
├── models/                       # Shared data models (DTOs)
│   ├── api/                      # API request/response DTOs
│   │   ├── access/               # Access management API DTOs
│   │   │   ├── GrantAccessRequest.kt     # Request DTO for granting access
│   │   │   ├── LLMModelDetails.kt        # Detailed LLM model info with access
│   │   │   ├── LLMProviderDetails.kt     # Detailed LLM provider info with access
│   │   │   ├── ModelSettingsDetails.kt   # Detailed model settings info with access
│   │   │   ├── OwnerInfo.kt              # Data class for owner details
│   │   │   ├── ResourceAccessDetails.kt  # Resource access details DTO
│   │   │   └── RevokeAccessRequest.kt    # Request DTO for revoking access
│   │   ├── admin/                # Admin-specific API DTOs
│   │   │   ├── AddUserToGroupRequest.kt  # Request DTO for adding user to group
│   │   │   ├── AssignRoleRequest.kt        # Request DTO for assigning a role to a user
│   │   │   ├── ChangePasswordRequest.kt    # Request DTO for changing user password
│   │   │   ├── CreateRoleRequest.kt        # Request DTO for creating a new role
│   │   │   ├── CreateUserGroupRequest.kt # Request DTO for creating a user group
│   │   │   ├── UpdatePasswordChangeRequiredRequest.kt # Request DTO for updating password change requirement
│   │   │   ├── UpdateRoleRequest.kt        # Request DTO for updating an existing role
│   │   │   ├── UpdateUserGroupRequest.kt # Request DTO for updating a user group
│   │   │   ├── UpdateUserRequest.kt        # Request DTO for updating user profile
│   │   │   └── UpdateUserStatusRequest.kt  # Request DTO for updating user status
│   │   ├── auth/                 # Authentication-related API DTOs
│   │   │   ├── LoginRequest.kt       # Login request DTO
│   │   │   ├── LoginResponse.kt      # Login response DTO
│   │   │   ├── RefreshTokenRequest.kt # Refresh token request DTO
│   │   │   └── RegisterRequest.kt    # User registration request DTO
│   │   ├── core/                 # Core API request/response DTOs
│   │   │   ├── AssignSessionToGroupRequest.kt # Request DTO for assigning session to group
│   │   │   ├── ChatClientEvent.kt        # Sealed interface for client-to-server WebSocket events
│   │   │   ├── ChatEvent.kt              # Sealed interface for server-sent chat events
│   │   │   ├── ChatStreamEvent.kt        # Chat stream event DTO
│   │   │   ├── CreateGroupRequest.kt     # Group creation request DTO
│   │   │   ├── CreateSessionRequest.kt   # Session creation request DTO
│   │   │   ├── ProcessNewMessageRequest.kt # Request DTO for processing new messages
│   │   │   ├── RenameGroupRequest.kt     # Group renaming request DTO
│   │   │   ├── UpdateMessageRequest.kt   # Message update request DTO
│   │   │   ├── UpdateSessionGroupRequest.kt # Request DTO for updating session group
│   │   │   ├── UpdateSessionLeafMessageRequest.kt # Request DTO for updating session leaf message
│   │   │   ├── UpdateSessionModelRequest.kt # Request DTO for updating session model
│   │   │   ├── UpdateSessionNameRequest.kt # Request DTO for updating session name
│   │   │   └── UpdateSessionSettingsRequest.kt # Request DTO for updating session settings
│   │   ├── llm/                  # LLM-specific API DTOs
│   │   │   ├── AddModelRequest.kt        # Request DTO for adding LLM models
│   │   │   ├── AddProviderRequest.kt     # Request DTO for adding LLM providers
│   │   │   ├── ApiKeyStatusResponse.kt   # Response DTO for API key status
│   │   │   └── UpdateProviderCredentialRequest.kt # Request DTO for updating provider credentials
│   │   ├── mcp/                  # MCP-specific API DTOs
│   │   │   ├── LocalMCPServerDtos.kt     # DTOs for local MCP server management
│   │   │   ├── LocalMCPToolCallRequest.kt # Request for LLM-initiated local MCP tool calls
│   │   │   ├── LocalMCPToolCallResult.kt  # Result of local MCP tool executions
│   │   │   └── LocalMCPToolRequests.kt   # DTOs for local MCP tool management
│   │   └── tool/                 # Tool-specific API DTOs
│   │       ├── CreateToolRequest.kt      # Request DTO for creating a new tool definition
│   │       ├── SetToolApprovalPreferenceRequest.kt # Request DTO for setting user tool approval preferences
│   │       ├── SetToolEnabledRequest.kt  # Request DTO for enabling/disabling a tool for a session
│   │       ├── SetToolsEnabledRequest.kt # Request DTO for batch enabling/disabling tools for a session
│   │       └── ToolCallApprovalResponse.kt # Response DTO for tool call approval requests
│   ├── core/                     # Core domain models
│   │   ├── ChatGroup.kt              # Chat group data model
│   │   ├── ChatMessage.kt            # Chat message with threading support
│   │   ├── ChatSession.kt            # Chat session data model
│   │   └── ChatSessionSummary.kt     # Session summary for lists
│   ├── llm/                      # LLM-related domain models
│   │   ├── LLMModel.kt               # LLM model configuration
│   │   ├── LLMModel_exensions.kt     # Extension functions for LLMModel
│   │   ├── LLMModelCapabilities.kt   # Data class for model capabilities
│   │   ├── LLMModelType.kt           # Enum for LLM model types
│   │   ├── LLMProvider.kt            # LLM provider configuration
│   │   ├── LLMProviderType.kt        # Enum for LLM provider types
│   │   └── ModelSettings.kt          # Model settings and parameters
│   ├── tool/                     # Tool-related domain models
│   │   ├── LocalMCPToolDefinition.kt # Data model for tools integrated from local MCP servers
│   │   ├── MiscToolDefinition.kt     # Data model for general-purpose tools
│   │   ├── ToolCall.kt               # Data model for a tool call
│   │   ├── ToolCallStatus.kt         # Enum for tool call status
│   │   ├── ToolDefinition.kt         # Sealed class for a tool definition hierarchy
│   │   ├── ToolType.kt               # Enum for tool types
│   │   └── UserToolApprovalPreference.kt # Data model for user tool approval preferences
│   └── user/                     # User-related domain models
│       ├── Permission.kt             # Permission data model
│       ├── Role.kt                   # Role data model
│       ├── User.kt                   # User account data model
│       ├── UserGroup.kt              # User group data model
│       ├── UserStatus.kt             # User status enum
│       └── UserWithDetails.kt        # Comprehensive user data for admin UIs
├── misc/                         # Miscellaneous utilities
│   ├── di/                       # Dependency injection abstractions
│   │   ├── DIContainer.kt        # Framework-agnostic DI interface
│   │   └── KoinDIContainer.kt    # Koin-specific DI implementation
│   └── transaction/              # Transaction management abstractions
│       ├── CoroutineContextExtensions.kt # Coroutine context extensions for transactions
│       ├── TransactionMarker.kt  # Coroutine context element for transactions
│       └── TransactionScope.kt   # Transaction scope interface
└── security/                     # Core security utilities
    ├── CryptoError.kt            # Sealed class for cryptographic errors
    ├── CryptoProvider.kt         # Interface for crypto operations
    ├── EncryptedSecret.kt        # Data class for encrypted secrets
    ├── EncryptionConfig.kt       # Configuration for encryption operations
    ├── EncryptionService.kt      # Service for envelope encryption
    ├── PasswordValidator.kt      # Password validation utility
    └── error/                    # Security-related error types
        └── PasswordValidationError.kt # Password validation error type
        
common/src/desktopAndroidMain/kotlin/eu/torvian/chatbot/
└── common/security/
    └── AESCryptoProvider.kt # JVM implementation of CryptoProvider
    
common/src/desktopTest/kotlin/eu/torvian/chatbot/
└── common/security/
    ├── AESCryptoProviderTest.kt # JVM tests for AESCryptoProvider
    └── EncryptionServiceTest.kt # JVM tests for EncryptionService
    
common/src/wasmJsMain/kotlin/eu/torvian/chatbot/
└── common/security/
    └── WasmJsWebCryptoProvider.kt # WASM/JS implementation of CryptoProvider
    
common/src/wasmJsTest/kotlin/eu/torvian/chatbot/
└── common/security/
    └── WasmJsWebCryptoProviderTest.kt # WASM/JS tests for WasmJsWebCryptoProvider
```

### 2. Server Module (`server/`)

**Purpose**: Backend logic including HTTP server, business services, data access, and external integrations.

**Package Structure**:
```
server/src/main/kotlin/eu/torvian/chatbot/server/
├── data/                         # Data access layer
│   ├── ModelSettingsMapper.kt    # Model settings mapper
│   ├── dao/                      # Data Access Objects (interfaces)
│   │   ├── ApiSecretDao.kt       # API secret management interface
│   │   ├── GroupDao.kt           # Group operations interface
│   │   ├── GroupOwnershipDao.kt  # Group ownership management interface
│   │   ├── LocalMCPServerDao.kt    # Interface for server-side MCP server data
│   │   ├── LocalMCPToolDefinitionDao.kt # Interface for MCP tool-server linkages
│   │   ├── MessageDao.kt         # Message operations interface
│   │   ├── ModelAccessDao.kt     # Model access management interface
│   │   ├── ModelDao.kt           # Model management interface
│   │   ├── ModelOwnershipDao.kt  # Model ownership management interface
│   │   ├── PermissionDao.kt      # permissions CRUD
│   │   ├── ProviderAccessDao.kt  # Provider access management interface
│   │   ├── ProviderDao.kt        # Model management interface
│   │   ├── ProviderOwnershipDao.kt # Provider ownership management interface
│   │   ├── RoleDao.kt            # roles CRUD
│   │   ├── RolePermissionDao.kt  # role-permission assignments
│   │   ├── SessionDao.kt         # Session operations interface
│   │   ├── SessionOwnershipDao.kt # Session ownership management interface
│   │   ├── SessionToolConfigDao.kt # Session-specific tool configuration DAO
│   │   ├── SettingsAccessDao.kt  # Settings access management interface
│   │   ├── SettingsDao.kt        # Settings management interface
│   │   ├── SettingsOwnershipDao.kt # Settings ownership management interface
│   │   ├── ToolCallDao.kt        # Tool call management interface
│   │   ├── ToolDefinitionDao.kt  # Tool definition management interface
│   │   ├── UserDao.kt            # User account management interface
│   │   ├── UserGroupDao.kt       # User group management interface
│   │   ├── UserRoleAssignmentDao.kt # User-role assignments
│   │   ├── UserSessionDao.kt     # User session management interface
│   │   ├── UserToolApprovalPreferenceDao.kt # Interface for managing user tool approval preferences
│   │   ├── error/                # DAO-specific error types
│   │   └── exposed/              # Exposed ORM implementations
│   ├── entities/                 # Database entity mappings
│   │   ├── ApiSecretEntity.kt    # API secret entity
│   │   ├── ChatSessionEntity.kt  # Chat session entity
│   │   ├── PermissionEntity.kt   # Permission entity
│   │   ├── RoleEntity.kt         # Role entity
│   │   ├── RolePermissionEntity.kt # Role-permission entity
│   │   ├── SessionCurrentLeafEntity.kt # Session current leaf entity
│   │   ├── UserEntity.kt         # User account entity
│   │   ├── UserGroupEntity.kt    # User group entity
│   │   ├── UserRoleAssignmentEntity.kt # User-role assignment entity
│   │   └── UserSessionEntity.kt  # User session entity
│   └── tables/                   # Exposed table definitions
│       └── mappers/              # Entity mapping utilities
├── domain/                       # Domain models and configuration
│   ├── config/                   # Configuration classes
│   │   ├── DatabaseConfig.kt     # Database configuration
│   │   └── SslConfig.kt          # SSL/TLS configuration for the server.
│   └── security/                 # Security-related classes
│       ├── AuthSchemes.kt        # Authentication schemes
│       ├── JwtConfig.kt          # JWT configuration
│       ├── LoginResult.kt        # Login result data model
│       └── UserContext.kt        # User context data model
├── koin/                         # Dependency injection modules
│   ├── configModule.kt           # Configuration DI module
│   ├── daoModule.kt              # DAO implementations DI module
│   ├── databaseModule.kt         # Database connection DI module
│   ├── miscModule.kt             # Miscellaneous services DI module
│   └── serviceModule.kt          # Service implementations DI module
├── ktor/                         # Ktor server setup
│   ├── configureKtor.kt          # Ktor server plugin configuration
│   ├── auth/
│   │   └── AuthUtils.kt          # Authentication utilities
│   ├── mappers/                  # Ktor mappers for API events
│   └── routes/                   # Ktor API routes
│       ├── ApiRoutesKtor.kt      # Ktor route configuration using type-safe Resources plugin
│       ├── ResourceExtensions.kt   # Extension functions for Ktor routing
│       ├── configureAuthRoutes.kt
│       ├── configureGroupRoutes.kt
│       ├── configureLocalMCPServerRoutes.kt # Ktor routes for local MCP server management
│       ├── configureLocalMCPToolRoutes.kt # Ktor routes for local MCP tool management
│       ├── configureMessageRoutes.kt
│       ├── configureModelRoutes.kt
│       ├── configureProviderRoutes.kt
│       ├── configureRoleRoutes.kt    # Ktor routes for role management
│       ├── configureSessionRoutes.kt
│       ├── configureSettingsRoutes.kt
│       ├── configureToolApprovalPreferenceRoutes.kt # Ktor routes for user tool approval preferences
│       ├── configureToolRoutes.kt    # Ktor routes for tool management
│       ├── configureUserGroupRoutes.kt # Ktor routes for user group management
│       └── configureUserRoutes.kt    # Ktor routes for user management
├── main/
│   ├── chatBotServerModule.kt    # Main Ktor application module for the chatbot server
│   ├── DataManager.kt            # Interface for managing database schema
│   ├── ExposedDataManager.kt     # Exposed data manager implementation
│   ├── mainModule.kt             # Koin module for main application setup
│   ├── ServerConfig.kt           # Server configuration data class
│   ├── ServerControlService.kt   # Server control service interface
│   ├── ServerControlServiceImpl.kt # Server control service implementation
│   ├── ServerInstanceInfo.kt     # Server instance information
│   ├── ServerMain.kt             # Main application entry point
│   └── ServerStatus.kt           # Server status sealed interface
├── service/                      # Business logic services
│   ├── core/                     # Core services
│   │   ├── ChatService.kt        # Service for processing chat messages
│   │   ├── GroupService.kt       # Group management service interface
│   │   ├── LLMModelService.kt    # LLM Model management service interface
│   │   ├── LLMProviderService.kt # LLM Provider management service interface
│   │   ├── LocalMCPServerService.kt # Service for managing server-side local MCP server IDs
│   │   ├── LocalMCPToolDefinitionService.kt # Service for managing local MCP tool definitions
│   │   ├── MessageEvent.kt       # Message event type for non-streaming
│   │   ├── MessageService.kt     # Message handling service interface
│   │   ├── MessageStreamEvent.kt # Message stream event type
│   │   ├── ModelSettingsService.kt # Model Settings management service interface
│   │   ├── RoleService.kt        # Role management service interface
│   │   ├── SessionService.kt     # Session management service interface
│   │   ├── ToolCallService.kt    # Interface for managing tool calls
│   │   ├── ToolService.kt        # Service for managing tool definitions and session configurations
│   │   ├── UserGroupService.kt   # User group management service interface
│   │   ├── UserService.kt        # User account management service interface
│   │   ├── UserToolApprovalPreferenceService.kt # Service for managing user tool approval preferences
│   │   ├── error/                # Service-specific error types
│   │   └── impl/                 # Core service implementations
│   ├── llm/                      # LLM interaction services
│   │   ├── ApiRequestConfig.kt   # Configuration details for making API requests
│   │   ├── ChatCompletionStrategy.kt # Strategy for chat completion
│   │   ├── GenericContentType.kt # Generic HTTP content type
│   │   ├── GenericHttpMethod.kt  # Generic HTTP method
│   │   ├── LLMApiClient.kt       # LLM API client interface
│   │   ├── LLMApiClientKtor.kt   # Ktor-based LLM API client implementation
│   │   ├── LLMCompletionError.kt # LLM completion error type
│   │   ├── LLMCompletionResult.kt # LLM completion result type
│   │   ├── LLMStreamChunk.kt     # LLM stream chunk type
│   │   ├── RawChatMessage.kt     # Raw chat message for LLM API communication
│   │   └── strategy/             # LLM completion strategy implementations
│   │       ├── OllamaApiModels.kt # Ollama API models (DTOs)
│   │       ├── OllamaChatStrategy.kt # Ollama chat completion strategy
│   │       ├── OpenAiApiModels.kt # OpenAI API models (DTOs)
│   │       └── OpenAIChatStrategy.kt # OpenAI chat completion strategy
│   ├── mcp/                      # MCP-specific services
│   │   ├── LocalMCPExecutor.kt       # Interface for managing tool execution on client-side MCP servers
│   │   ├── LocalMCPExecutorError.kt  # Error types for local MCP tool execution
│   │   └── LocalMCPExecutorEvent.kt  # Events for local MCP tool call lifecycle
│   ├── security/                 # Security services
│   │   ├── AuthenticationService.kt # Authentication service interface
│   │   ├── AuthorizationService.kt # Authorization service interface
│   │   ├── BCryptPasswordService.kt # BCrypt password service implementation
│   │   ├── CertificateManager.kt   # Interface for managing server SSL certificates.
│   │   ├── CredentialManager.kt  # Credential management interface
│   │   ├── DbEncryptedCredentialManager.kt # Database-backed credential manager
│   │   ├── DefaultCertificateManager.kt # Default implementation for server SSL certificate management.
│   │   ├── PasswordService.kt    # Password service interface
│   │   ├── ResourceType.kt       # Resource type enumeration
│   │   └── error/                # Domain-specific error types
│   ├── setup/                    # Initial setup services
│   │   ├── DataInitializer.kt        # Interface for defining startup data setup tasks
│   │   ├── InitializationCoordinator.kt # Orchestrates and runs initializers sequentially
│   │   ├── InitialSetupService.kt    # Service for initial database and user setup
│   │   ├── ToolDefinitionInitializer.kt # Initializes default tool definitions
│   │   └── UserAccountInitializer.kt # Initializes default admin user, roles, and groups
│   └── tool/                     # Tool execution services
│       ├── ToolExecutor.kt           # Interface for generic tool execution
│       ├── ToolExecutorFactory.kt    # Manages and provides tool executors
│       ├── error/                    # Tool execution error types
│       │   └── ToolExecutionError.kt   # Structured error hierarchy for tool execution
│       └── impl/                     # Tool executor implementations
│           ├── WeatherToolExecutor.kt    # Provides mock weather data
│           └── WebSearchToolExecutor.kt  # Performs web searches using DuckDuckGo
└── utils/                        # Utility classes
    └── transactions/             # Transaction management
        └── ExposedTransactionScope.kt # Exposed-specific transaction scope
```

**Test Structure**:
```
server/src/test/kotlin/eu/torvian/chatbot/server/
├── data/                         # Data layer tests
│   └── exposed/                  # Exposed DAO tests
├── ktor/routes/                  # Ktor route tests
├── service                      # Service layer tests
│   ├── core/impl/               # Core service tests
│   │   ├── ChatServiceImplTest.kt    # Unit tests for ChatService message processing
│   │   ├── GroupServiceImplTest.kt
│   │   ├── LLMModelServiceImplTest.kt
│   │   ├── LLMProviderServiceImplTest.kt
│   │   ├── MessageServiceImplTest.kt
│   │   ├── ModelSettingsServiceImplTest.kt
│   │   └── SessionServiceImplTest.kt
│   ├── llm/                      # LLM service tests
│   │   ├── LLMApiClientKtorTest.kt
│   │   ├── LLMApiClientStub.kt
│   │   └── strategy/             # LLM strategy tests
│   │       ├── OllamaChatStrategyTest.kt
│   │       └── OpenAIChatStrategyTest.kt
│   ├── security/                 # Security service tests
│   │   ├── AESCryptoProviderTest.kt
│   │   ├── DbEncryptedCredentialManagerTest.kt
│   │   └── EncryptionServiceTest.kt
│   └── setup/                    # Setup service tests
│       ├── InitializationCoordinatorTest.kt # Tests for InitializationCoordinator
│       ├── InitialSetupServiceTest.kt
│       ├── ToolDefinitionInitializerTest.kt # Tests for ToolDefinitionInitializer
│       └── UserAccountInitializerTest.kt # Tests for UserAccountInitializer
└── testutils/                    # Test utilities
    ├── auth/
    │   └── TestAuthHelper.kt     # Test authentication helper
    ├── data/                     # Data test utilities
    │   ├── ExposedTestDataManager.kt # Test data management
    │   ├── Table.kt              # Table enumeration for tests
    │   ├── TestDataManager.kt    # Test data manager interface
    │   ├── TestDataSet.kt        # Test data set container
    │   └── TestDefaults.kt       # Predefined test data values
    ├── koin/                     # Test DI modules
    │   ├── defaultTestConfigModule.kt # Default test config module
    │   ├── defaultTestContainer.kt # Default test container
    │   ├── testDatabaseModule.kt # Koin module for test database configuration.
    │   └── testSetupModule.kt    # Test setup DI module
    └── ktor/                     # Ktor test utilities
        └── myTestApplication.kt  # Custom test application setup
```

### 3. App Module (`app/`)

**Purpose**: Desktop application frontend built with Compose Multiplatform. (Android and WebAssembly support planned for future versions.)

**Package Structure**:
```
app/src/commonMain/kotlin/eu/torvian/chatbot/app/  # Common code for all app targets
├── compose/          # Compose UI components
│   ├── AppShell.kt   # Main entry point for routing based on AuthState (Loading, Auth, Unauth)
│   ├── AuthenticationFlow.kt # UI flow wrapper for Login/Register screens
│   ├── MainApplicationFlow.kt # UI flow wrapper for Chat/Settings screens (authenticated)
│   ├── ChatScreen.kt # Main chat interface (displays session list, chat messages, input area)
│   ├── ChatScreenContent.kt # Stateless content composable for chat interface
│   ├── SettingsScreen.kt # Main settings interface (contains tabs for providers, models, model settings, and MCP servers)
│   ├── UserMenu.kt   # User menu composable
│   ├── admin/        # Admin UI components
│   │   ├── AdminScreen.kt
│   │   ├── usergroups/ # User group management UI components
│   │   └── users/    # User management UI components
│   ├── auth/          # Authentication UI components
│   │   ├── LoginScreen.kt
│   │   ├── RegisterScreen.kt
│   │   └── ... other auth components ...
│   ├── chatarea/     # Chat area components
│   │   ├── ChatArea.kt
│   │   └── ... other chat area components ...
│   ├── common/       # Common compose components
│   │   ├── ConfigFormComponents.kt # Reusable form components
│   │   ├── ErrorStateDisplay.kt # Error state display component
│   │   ├── LoadingOverlay.kt  # Loading overlay component
│   │   ├── OverflowTooltipText.kt # Text with overflow tooltip
│   │   ├── PlainTooltipBox.kt # Plain tooltip box component
│   │   └── ScrollbarWrapper.kt # Scrollbar wrapper component
│   ├── preview/     # Compose UI previews
│   ├── sessionlist/  # Session list components
│   │   ├── SessionListPanel.kt
│   │   └── ... other session list components ...
│   ├── settings/    # Settings components
│   │   ├── SettingsScreen.kt
│   │   └── ... other settings components ...
│   ├── snackbar/    # Snackbar components
│   └── topbar/    # Top bar components for dynamic content
├── database/        # Local database management (SQLDelight)
│   ├── dao/         # Data Access Objects for local DB
│   │   ├── EncryptedSecretLocalDao.kt # Interface for local secret DAO
│   │   ├── EncryptedSecretLocalDaoImpl.kt # SQLDelight implementation for secret DAO
│   │   ├── LocalMCPServerLocalDao.kt  # Interface for local MCP server DAO
│   │   ├── LocalMCPServerLocalDaoImpl.kt # SQLDelight implementation for MCP server DAO
│   │   ├── error/                   # Local DAO error types
│   │   └── isForeignKeyConstraintException.kt # Platform-specific exception checker
│   ├── model/       # Local database entity models
│   │   └── EncryptedSecretEntity.kt # Entity for encrypted secrets
│   ├── DriverFactory.kt # Expect declaration for platform-specific DB driver
│   └── LocalDatabaseProvider.kt # Provides the SQLDelight database instance
├── domain/          # Domain models specific to the *application's presentation layer*
│   ├── contracts/    # UI State and Action contracts (interfaces between UI and ViewModels)
│   │   ├── DataState.kt  # Data state contract
│   │   ├── DataStateExtensions.kt # Extension functions for DataState
│   │   ├── FormMode.kt  # Form mode enum
│   │   ├── GrantAccessFormState.kt # Form state for granting resource access
│   │   ├── ModelConfigData.kt  # Model configuration data
│   │   ├── ModelFormState.kt  # Model form state
│   │   ├── ModelSettingsDialogState.kt  # Model settings dialog state
│   │   ├── ModelSettingsFormState.kt  # Model settings form state
│   │   ├── ModelsDialogState.kt  # Models dialog state
│   │   ├── ProvidersDialogState.kt  # Providers dialog state
│   │   ├── SessionListData.kt    # Session list data structure
│   │   └── SessionListDialogState.kt  # Session list dialog state
│   ├── events/        # Domain events (e.g., user actions, system responses)
│   │   ├── ApiRequestError.kt # API request error event
│   │   ├── AppError.kt  # Global error event
│   │   ├── AppEvent.kt  # Base event class
│   │   ├── AppSuccess.kt # Global success event
│   │   ├── AppWarning.kt # Global warning event
│   │   ├── GenericAppError.kt # Generic application error event
│   │   ├── GenericAppWarning.kt # Generic application warning event
│   │   ├── RepositoryAppError.kt # Repository error event
│   │   └── SnackbarInteractionEvent.kt # Snackbar interaction event
│   ├── models/       # UI-specific domain models
│   │   └── LocalMCPServer.kt # Data model for local MCP server configuration
│   └── navigation/   # Navigation related classes
│       └── AppRoute.kt  # Application routes
├── koin/            # Koin modules
│   ├── appModule.kt  # main app DI module
│   └── databaseModule.kt # DI module for local database
├── main/            
│   └── AppConfig.kt  # Application configuration
├── repository/      # Data repository for frontend
│   ├── AuthRepository.kt   # Interface for managing user authentication state
│   ├── AuthState.kt        # Sealed class representing auth status (Loading, Auth, Unauth)
│   ├── GroupRepository.kt  # Group repository
│   ├── LocalMCPServerRepository.kt # Repository for managing client-side MCP server configurations
│   ├── LocalMCPToolRepository.kt # Repository for managing local MCP tool definitions
│   ├── ModelRepository.kt  # Model repository
│   ├── ProviderRepository.kt # Provider repository
│   ├── RepositoryError.kt  # Repository error hierarchy
│   ├── RoleRepository.kt   # Interface for managing user roles
│   ├── SessionRepository.kt  # Session repository
│   ├── ModelSettingsRepository.kt # Model settings repository
│   ├── ToolRepository.kt   # Interface for managing tool definitions
│   ├── UserRepository.kt   # Interface for managing user accounts and details
│   ├── UserGroupRepository.kt # User group management repository
│   └── impl/             # Repository implementations
├── service/          # Frontend services (API clients)
│   ├── api/          # API interfaces
│   │   ├── AuthApi.kt
│   │   ├── ChatApi.kt  
│   │   ├── GroupApi.kt
│   │   ├── LocalMCPServerApi.kt # Interface for managing local MCP server IDs
│   │   ├── LocalMCPToolApi.kt # Interface for managing local MCP tool definitions
│   │   ├── ModelApi.kt
│   │   ├── ProviderApi.kt
│   │   ├── RoleApi.kt      # Interface for managing user roles
│   │   ├── SessionApi.kt
│   │   ├── SettingsApi.kt
│   │   ├── ToolApi.kt      # Interface for tool management
│   │   ├── UserApi.kt      # Interface for user management operations
│   │   └── ktor/       # Ktor-based API client implementations
│   │       ├── BaseApiClient.kt  # Base API client implementation
│   │       ├── configureHttpClient.kt # Common Ktor HTTP client configuration.
│   │       ├── createPlatformHttpClient.kt # Expect declaration for platform-specific HTTP client creation.
│   │       ├── HttpClientWebSocketExtension.kt # Helper for dynamic WebSocket protocol selection
│   │       ├── KtorChatApiClient.kt
│   │       ├── KtorGroupApiClient.kt
│   │       ├── KtorLocalMCPServerApiClient.kt # Ktor client for local MCP server API
│   │       ├── KtorLocalMCPToolApiClient.kt # Ktor client for local MCP tool API
│   │       └── ...
│   ├── auth/         # Auth services (token storage, account management, http client)
│   │   ├── AccountData.kt # Account data model (for local storage)
│   │   ├── AuthenticationFailureEvent.kt # Authentication failure event
│   │   ├── createAuthenticatedHttpClient.kt # Authenticated HTTP client creation
│   │   ├── FilePermissions.kt # File permissions utility
│   │   ├── FileSystemTokenStorage.kt # File system-based token storage implementation
│   │   ├── TokenStorage.kt  # Token storage interface
│   │   └── TokenStorageError.kt # Token storage error hierarchy
│   ├── mcp/          # MCP-specific client-side services
│   │   ├── LocalMCPServerManager.kt # Interface for orchestrating local MCP server workflows
│   │   ├── LocalMCPServerManagerError.kt # Error hierarchy for local MCP server manager operations
│   │   ├── LocalMCPServerManagerImpl.kt # Implementation for local MCP server manager
│   │   ├── LocalMCPServerOverview.kt # Data class for aggregate server status
│   │   ├── LocalMCPServerProcessManager.kt # Interface for platform-independent process control
│   │   ├── LocalMCPToolCallMediator.kt # Interface for LLM-to-MCP tool call mediation
│   │   ├── LocalMCPToolCallMediatorDummy.kt # Dummy implementation for platforms not supporting local MCP tool calls
│   │   ├── LocalMCPToolCallMediatorImpl.kt # Implementation for local MCP tool call mediation
│   │   ├── MCPClient.kt # Data class representing active MCP client connections
│   │   ├── MCPClientService.kt # Interface for managing MCP client connections
│   │   ├── MCPClientServiceError.kt # Error hierarchy for MCP client service operations
│   │   ├── MCPClientServiceImpl.kt # Implementation for MCP client service (moved to commonMain)
│   │   ├── ProcessManagerError.kt # Error hierarchy for process operations
│   │   └── ProcessStatus.kt # Status and state for server processes
│   ├── misc/          # Miscellaneous frontend services
│   │   ├── EncryptedSecretService.kt # Interface for managing local encrypted secrets
│   │   ├── EncryptedSecretServiceImpl.kt # Implementation for encrypted secret service
│   │   └── EventBus.kt  # Event bus for frontend events
│   └── security/      # Frontend security services
│       ├── CertificateDetails.kt # DTO for presenting certificate information to the user.
│       ├── CertificateStorage.kt # Interface for managing server certificates for pinning.
│       ├── CertificateStorageError.kt # Error types for certificate storage operations.
│       ├── CertificateTrustService.kt # Service to mediate user trust decisions for certificates.
│       └── FileSystemCertificateStorage.kt # KMP-compatible implementation of certificate storage.
├── utils/            # Utility classes
│   ├── misc/       # Miscellaneous utilities
│   │   ├── ioDispatcher.kt  # IO dispatcher (expect/actual)
│   │   └── KmpLogger.kt  # KMP-compatible logger
│   └── transaction/  # Local database transaction utilities
│       ├── SqlDelightTransactionScope.kt # SQLDelight transaction scope implementation
│       └── databaseDispatcher.kt # Expect/actual for DB dispatcher
└── viewmodel/        # ViewModels for UI state management
    ├── ModelConfigViewModel.kt # Model Config ViewModel (manages LLM model state)
    ├── ModelSettingsViewModel.kt # Model Settings ViewModel (manages model settings state)
    ├── ProviderConfigViewModel.kt # Provider Config ViewModel (manages LLM provider state)
    ├── SessionListViewModel.kt # Session List ViewModel (manages session list state)
    ├── admin/          # Admin-specific ViewModels
    │   ├── UserGroupManagementState.kt # State for user group management UI
    │   ├── UserGroupManagementViewModel.kt # ViewModel for user group management
    │   ├── UserManagementState.kt      # State for user management UI
    │   └── UserManagementViewModel.kt  # ViewModel for admin user management
    ├── auth/
    │   ├── AuthDialogState.kt # Auth dialog state
    │   └── AuthViewModel.kt  # Authentication ViewModel
    ├── chat/
    │   ├── ChatViewModel.kt  # Chat ViewModel (manages chat session state)
    │   ├── state/  # Chat ViewModel state
    │   │   ├── ChatAreaDialogState.kt  # Chat area dialog state
    │   │   ├── ChatState.kt  # Chat state data class
    │   │   └── ChatStateImpl.kt  # Chat state implementation
    │   ├── usecase/  # Chat ViewModel use cases
    │   │   ├── LoadSessionUseCase.kt  # Load session use case
    │   │   ├── SendMessageUseCase.kt  # Send message use case
    │   │   └── ... other use cases ...
    │   └── util/  # Chat ViewModel utilities
    │       └── ThreadBuilder.kt  # Thread builder utility
    └── common/  # Common ViewModel utilities
        ├── CoroutineScopeProvider.kt  # Coroutine scope provider
        └── ErrorNotifier.kt  # Error notifier utility
     
app/src/commonMain/composeResources/  # Compose resources (strings, etc.)
├── values/  # Default resources
│   └── strings.xml  # String resources
├── values-es/  # Spanish resources
│    └── strings.xml  # Spanish string resources
└── ... other resources ...

app/src/commonMain/sqldelight/eu/torvian/chatbot/app/database/ # SQLDelight schema definitions
├── EncryptedSecretTable.sq # SQLDelight table for encrypted secrets
└── LocalMCPServerLocalTable.sq # SQLDelight table for client-side local MCP server configurations

app/src/commonTest/kotlin/eu/torvian/chatbot/app/  # Common tests
└── testutils/     # Test utilities
    ├── data/
    │   └── TestData.kt   # Predefined test data (DTOs, etc.)
    └── viewmodel/
        └── FlowTestUtils.kt  # Test utilities for Flow-based ViewModels

app/src/desktopMain/kotlin/eu/torvian/chatbot/app/  # Desktop-specific implementations
├── compose/
│   └── ... desktop-specific UI components ...
├── database/     # Desktop-specific database implementations
│   ├── DriverFactoryDesktop.kt # Desktop implementation for SQLDelight driver
│   └── dao/
│       └── ExceptionCheckerDesktop.kt # Desktop implementation for FK exception check
├── main/         # Main entry point
│   └── AppMain.kt    # Application entry point, setup (UI launch, DI)
├── service/mcp/  # Desktop-specific MCP services
│   └── LocalMCPServerProcessManagerDesktop.kt # Desktop implementation for local MCP server process manager
└── utils/        
    ├── misc/       # Miscellaneous utilities
    │   └── createKmpLogger.desktop.kt # Desktop-specific KMP logger
    └── transaction/ # Desktop-specific transaction implementations
        └── databaseDispatcher.desktop.kt # Desktop implementation for DB dispatcher

app/src/desktopTest/kotlin/eu/torvian/chatbot/app/ # Desktop-specific tests
├── compose/          # Compose UI component tests
│   ├── ChatAreaTest.kt
│   └── common/       # Common compose component tests
│       └── LoadingOverlayTest.kt
├── database/         # Local database tests
│   └── dao/          # Local DAO tests
│       ├── EncryptedSecretLocalDaoTest.kt
│       └── LocalMCPServerLocalDaoTest.kt
├── service/
│   ├── api/ktor/    # Ktor API client tests
│   │   ├── KtorChatApiClientTest.kt  
│   │   ├── KtorSessionApiClientTest.kt
│   │   └── ...
│   ├── mcp/
│   │   └── LocalMCPServerProcessManagerTest.kt # Unit tests for process management
│   └── misc/        # Miscellaneous service tests
│       └── EncryptedSecretServiceImplTest.kt
├── testutils/           
│   └── viewmodel/        
│        └── TestMockkExtensions.kt # Mockk test extensions
└── viewmodel/
    └── ChatViewModelTest.kt

app/src/desktopAndroidMain/kotlin/eu/torvian/chatbot/app/  # Desktop- and Android-specific implementations
├── service/api/ktor/
│   └── createPlatformHttpClient.kt # Actual implementation of platform HTTP client for Desktop/Android.
└── security/ 
    └── CustomTrustManager.kt # Custom X.509 trust manager for certificate pinning. 

app/src/androidMain/kotlin/eu/torvian/chatbot/app/  # Android-specific implementations
├── compose/
│   └── ... android-specific UI components ...
├── database/     # Android-specific database implementations
│   ├── DriverFactoryAndroid.kt # Android implementation for SQLDelight driver
│   └── dao/
│       └── ExceptionCheckerAndroid.kt # Android implementation for FK exception check
├── main/         # Main entry point
│   └── MainActivity.kt    # Application entry point, setup (UI launch, DI)
├── service/mcp/  # Android-specific MCP services
│   ├── LocalMCPProcessManagerAndroid.kt # Placeholder for Android local MCP process manager
│   └── MCPClientServiceAndroid.kt # Android-specific type alias for MCPClientService
└── utils/        
    ├── misc/       # Miscellaneous utilities
    │   └── KmpLogger.android.kt # Android-specific KMP logger
    └── transaction/ # Android-specific transaction implementations
        └── databaseDispatcher.android.kt # Android implementation for DB dispatcher

app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/  # WebAssembly-specific implementations
├── compose/
│   └── ... wasm-specific UI components ...
├── database/     # WasmJs-specific database implementations
│   └── dao/
│       └── ExceptionCheckerWasm.kt # WasmJs implementation for FK exception check
├── main/         # Main entry point
│   └── AppMain.kt    # Application entry point, setup (UI launch, DI)
├── service/api/ktor/ # Ktor API client WASM/JS-specific implementations
│   └── createPlatformHttpClient.kt # Actual implementation of platform HTTP client for WASM/JS.
├── service/security/ # WASM/JS-specific security implementations
│   └── BrowserCertificateStorage.kt # No-op certificate storage for WASM/JS, relying on browser's TLS.
└── utils/        
    ├── misc/       # Miscellaneous utilities
    │   └── createKmpLogger.wasmJs.kt # WebAssembly-specific KMP logger
    └── transaction/ # WasmJs-specific transaction implementations
        └── databaseDispatcher.wasmJs.kt # WasmJs implementation for DB dispatcher
```

## Architecture Overview

### Module Dependencies
- **app** depends on **common**
- **server** depends on **common**
- **app** and **server** are independent of each other

### Data Flow
1. **Common Module**: Provides shared data models (DTOs) used for API communication
2. **Server Module**: Implements business logic, data persistence, and external service integration
3. **App Module**: Provides the user interface and handles user interactions

### Key Architectural Patterns
- **Layered Architecture**: Clear separation between presentation, business, and data layers
- **Repository Pattern**: DAO interfaces with concrete implementations
- **Dependency Injection**: Koin-based DI throughout the application
- **Transaction Management**: Abstracted transaction handling for database operations

## Build Configuration

### Gradle Modules
- **Root Project**: Coordinates all modules and shared configuration
- **build-logic**: Custom Gradle convention plugins for consistent build configuration
- **common**: Kotlin library with serialization support
- **server**: Kotlin application with Ktor server and Exposed ORM
- **app**: Compose for Desktop application with native distribution support

This structure provides a clean, maintainable, and testable architecture suitable for a desktop AI chatbot application.