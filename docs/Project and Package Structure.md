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
│   ├── AddModelRequest.kt        # Request DTO for adding LLM models
│   ├── AddProviderRequest.kt     # Request DTO for adding LLM providers
│   ├── ApiKeyStatusResponse.kt   # Response DTO for API key status
│   ├── AssignSessionToGroupRequest.kt # Request DTO for assigning session to group
│   ├── ChatGroup.kt              # Chat group data model
│   ├── ChatMessage.kt            # Chat message with threading support
│   ├── ChatSession.kt            # Chat session data model
│   ├── ChatSessionSummary.kt     # Session summary for lists
│   ├── ChatStreamEvent.kt        # Chat stream event DTO
│   ├── CreateGroupRequest.kt     # Group creation request DTO
│   ├── CreateSessionRequest.kt   # Session creation request DTO
│   ├── LLMModel.kt               # LLM model configuration
│   ├── LLMModel_exensions.kt     # Extension functions for LLMModel
│   ├── LLMModelCapabilities.kt   # Data class for model capabilities
│   ├── LLMModelType.kt           # Enum for LLM model types
│   ├── LLMProvider.kt            # LLM provider configuration
│   ├── LLMProviderType.kt        # Enum for LLM provider types
│   ├── ModelSettings.kt          # Model settings and parameters
│   ├── ProcessNewMessageRequest.kt # Request DTO for processing new messages
│   ├── RenameGroupRequest.kt     # Group renaming request DTO
│   ├── UpdateMessageRequest.kt   # Message update request DTO
│   ├── UpdateProviderCredentialRequest.kt # Request DTO for updating provider credentials
│   ├── UpdateSessionGroupRequest.kt # Request DTO for updating session group
│   ├── UpdateSessionLeafRequest.kt # Request DTO for updating session leaf message
│   ├── UpdateSessionModelRequest.kt # Request DTO for updating session model
│   ├── UpdateSessionNameRequest.kt # Request DTO for updating session name
│   ├── UpdateSessionSettingsRequest.kt # Request DTO for updating session settings
│   ├── User.kt                   # User account data model
│   ├── UserGroup.kt              # User group data model
│   ├── UserWithDetails.kt        # Comprehensive user data for admin UIs
│   ├── admin/                    # Admin-specific models
│   │   ├── AssignRoleRequest.kt        # Request DTO for assigning a role to a user
│   │   ├── ChangePasswordRequest.kt    # Request DTO for changing user password
│   │   ├── CreateRoleRequest.kt        # Request DTO for creating a new role
│   │   ├── UpdateRoleRequest.kt        # Request DTO for updating an existing role
│   │   ├── UpdateUserRequest.kt        # Request DTO for updating user profile
│   │   └── UpdateUserStatusRequest.kt  # Request DTO for updating user status
│   └── auth/                     # Authentication-related models
│       ├── LoginRequest.kt       # Login request DTO
│       ├── LoginResponse.kt      # Login response DTO
│       ├── RefreshTokenRequest.kt # Refresh token request DTO
│       └── RegisterRequest.kt    # User registration request DTO
├── misc/                         # Miscellaneous utilities
│   └── di/                       # Dependency injection abstractions
│       ├── DIContainer.kt        # Framework-agnostic DI interface
│       └── KoinDIContainer.kt    # Koin-specific DI implementation
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
│   │   ├── MessageDao.kt         # Message operations interface
│   │   ├── ModelDao.kt           # Model management interface
│   │   ├── PermissionDao.kt      # permissions CRUD
│   │   ├── RoleDao.kt            # roles CRUD
│   │   ├── RolePermissionDao.kt  # role-permission assignments
│   │   ├── SessionDao.kt         # Session operations interface
│   │   ├── SessionOwnershipDao.kt # Session ownership management interface
│   │   ├── SettingsDao.kt        # Settings management interface
│   │   ├── UserDao.kt            # User account management interface
│   │   ├── UserGroupDao.kt       # User group management interface
│   │   ├── UserRoleAssignmentDao.kt # User-role assignments
│   │   ├── UserSessionDao.kt     # User session management interface
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
│   │   └── DatabaseConfig.kt     # Database configuration
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
│   └── routes/                   # Ktor API routes
│       ├── ApiRoutesKtor.kt      # Ktor route configuration using type-safe Resources plugin
│       ├── configureAuthRoutes.kt
│       ├── configureGroupRoutes.kt
│       ├── configureMessageRoutes.kt
│       ├── configureModelRoutes.kt
│       ├── configureProviderRoutes.kt
│       ├── configureRoleRoutes.kt    # Ktor routes for role management
│       ├── configureSessionRoutes.kt
│       ├── configureSettingsRoutes.kt
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
│   │   ├── GroupService.kt       # Group management service interface
│   │   ├── LLMModelService.kt    # LLM Model management service interface
│   │   ├── LLMProviderService.kt # LLM Provider management service interface
│   │   ├── MessageService.kt     # Message handling service interface
│   │   ├── MessageStreamEvent.kt # Message stream event type
│   │   ├── ModelSettingsService.kt # Model Settings management service interface
│   │   ├── RoleService.kt        # Role management service interface
│   │   ├── SessionService.kt     # Session management service interface
│   │   ├── UserService.kt        # User account management service interface
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
│   │   └── strategy/             # LLM completion strategy implementations
│   │       ├── OllamaApiModels.kt # Ollama API models (DTOs)
│   │       ├── OllamaChatStrategy.kt # Ollama chat completion strategy
│   │       ├── OpenAiApiModels.kt # OpenAI API models (DTOs)
│   │       └── OpenAIChatStrategy.kt # OpenAI chat completion strategy
│   ├── security/                 # Security services
│   │   ├── AuthenticationService.kt # Authentication service interface
│   │   ├── AuthorizationService.kt # Authorization service interface
│   │   ├── BCryptPasswordService.kt # BCrypt password service implementation
│   │   ├── CredentialManager.kt  # Credential management interface
│   │   ├── DbEncryptedCredentialManager.kt # Database-backed credential manager
│   │   ├── PasswordService.kt    # Password service interface
│   │   ├── ResourceType.kt       # Resource type enumeration
│   │   └── error/                # Domain-specific error types
│   └── setup/                    # Initial setup services
│       └── InitialSetupService.kt # Service for initial database and user setup
└── utils/                        # Utility classes
    └── transactions/             # Transaction management
        ├── ExposedTransactionScope.kt # Exposed-specific transaction scope
        └── TransactionScope.kt   # Transaction scope interface
```

**Test Structure**:
```
server/src/test/kotlin/eu/torvian/chatbot/server/
├── data/                         # Data layer tests
│   └── exposed/                  # Exposed DAO tests
├── ktor/routes/                  # Ktor route tests
├── service                      # Service layer tests
│   ├── core/impl/               # Core service tests
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
│       └── InitialSetupServiceTest.kt
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
│   ├── SettingsScreen.kt # Settings configuration interface (providers, models, settings)
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
│   └── snackbar/    # Snackbar components
├── domain/          # Domain models specific to the *application's presentation layer*
│   ├── contracts/    # UI State and Action contracts (interfaces between UI and ViewModels)
│   │   ├── DataState.kt  # Data state contract
│   │   ├── FormMode.kt  # Form mode enum
│   │   ├── ModelConfigData.kt  # Model configuration data
│   │   ├── ModelFormState.kt  # Model form state
│   │   ├── ModelsDialogState.kt  # Models dialog state
│   │   ├── ProvidersDialogState.kt  # Providers dialog state
│   │   ├── SessionListData.kt    # Session list data structure
│   │   ├── SessionListDialogState.kt  # Session list dialog state
│   │   ├── SettingsConfigData.kt  # Settings configuration data
│   │   ├── SettingsDialogState.kt  # Settings dialog state
│   │   └── SettingsFormState.kt  # Settings form state
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
│   └── navigation/   # Navigation related classes
│       └── AppRoute.kt  # Application routes
├── koin/            # Koin modules 
│   └── appModule.kt  # main app DI module
├── main/            
│   └── AppConfig.kt  # Application configuration
├── repository/      # Data repository for frontend
│   ├── AuthRepository.kt   # Interface for managing user authentication state
│   ├── AuthState.kt        # Sealed class representing auth status (Loading, Auth, Unauth)
│   ├── GroupRepository.kt  # Group repository
│   ├── ModelRepository.kt  # Model repository
│   ├── ProviderRepository.kt # Provider repository
│   ├── RepositoryError.kt  # Repository error hierarchy
│   ├── RoleRepository.kt   # Interface for managing user roles
│   ├── SessionRepository.kt  # Session repository
│   ├── SettingsRepository.kt # Settings repository
│   ├── UserRepository.kt   # Interface for managing user accounts and details
│   └── impl/             # Repository implementations
├── service/          # Frontend services (API clients)
│   ├── api/          # API interfaces
│   │   ├── AuthApi.kt
│   │   ├── ChatApi.kt  
│   │   ├── GroupApi.kt
│   │   ├── ModelApi.kt
│   │   ├── ProviderApi.kt
│   │   ├── RoleApi.kt      # Interface for managing user roles
│   │   ├── SessionApi.kt
│   │   ├── SettingsApi.kt
│   │   ├── UserApi.kt      # Interface for user management operations
│   │   └── ktor/       # Ktor-based API client implementations
│   │       ├── BaseApiClient.kt  # Base API client implementation
│   │       ├── createHttpClient.kt # Ktor HTTP client setup
│   │       ├── KtorChatApiClient.kt
│   │       ├── KtorGroupApiClient.kt
│   │       └── ...
│   └── misc/          # Miscellaneous frontend services
│       └── EventBus.kt  # Event bus for frontend events
├── utils/            # Utility classes
│   └── misc/       # Miscellaneous utilities
│       ├── ioDispatcher.kt  # IO dispatcher (expect/actual)
│       └── KmpLogger.kt  # KMP-compatible logger
└── viewmodel/        # ViewModels for UI state management
    ├── ModelConfigViewModel.kt # Model Config ViewModel (manages LLM model state)
    ├── ProviderConfigViewModel.kt # Provider Config ViewModel (manages LLM provider state)
    ├── SessionListViewModel.kt # Session List ViewModel (manages session list state)
    ├── SettingsConfigViewModel.kt # Settings Config ViewModel (manages model settings state)
    ├── admin/          # Admin-specific ViewModels
    │   ├── UserManagementState.kt      # State for user management UI
    │   └── UserManagementViewModel.kt  # ViewModel for admin user management
    ├── auth/
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
   
app/src/commonTest/kotlin/eu/torvian/chatbot/app/  # Common tests
└── testutils/     # Test utilities
    ├── data/
    │   └── TestData.kt   # Predefined test data (DTOs, etc.)
    └── viewmodel/
        └── FlowTestUtils.kt  # Test utilities for Flow-based ViewModels

app/src/desktopMain/kotlin/eu/torvian/chatbot/app/  # Desktop-specific implementations
├── compose/
│   └── ... desktop-specific UI components ...
├── main/         # Main entry point
│   └── AppMain.kt    # Application entry point, setup (UI launch, DI)
└── utils/        
    └── misc/       # Miscellaneous utilities
        └── createKmpLogger.desktop.kt # Desktop-specific KMP logger

app/src/desktopTest/kotlin/eu/torvian/chatbot/app/ # Desktop-specific tests
├── compose/          # Compose UI component tests
│   ├── ChatAreaTest.kt 
│   └── common/       # Common compose component tests
│       └── LoadingOverlayTest.kt
├── service/api/ktor/    # Ktor API client tests
│   ├── KtorChatApiClientTest.kt  
│   ├── KtorSessionApiClientTest.kt 
│   └── ... 
├── testutils/           
│   └── viewmodel/        
│        └── TestMockkExtensions.kt # Mockk test extensions
└── viewmodel/
    └── ChatViewModelTest.kt
    
app/src/androidMain/kotlin/eu/torvian/chatbot/app/  # Android-specific implementations
├── compose/
│   └── ... android-specific UI components ...
├── main/         # Main entry point
│   └── MainActivity.kt    # Application entry point, setup (UI launch, DI)
└── utils/        
    └── misc/       # Miscellaneous utilities
        └── KmpLogger.android.kt # Android-specific KMP logger
        
app/src/wasmJsMain/kotlin/eu/torvian/chatbot/app/  # WebAssembly-specific implementations
├── compose/
│   └── ... wasm-specific UI components ...
├── main/         # Main entry point
│   └── AppMain.kt    # Application entry point, setup (UI launch, DI)
└── utils/        
    └── misc/       # Miscellaneous utilities
        └── createKmpLogger.wasmJs.kt # WebAssembly-specific KMP logger
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