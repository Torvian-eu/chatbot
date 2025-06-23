# Project and Package Structure

## Overview

This is a multi-module Kotlin desktop application for AI chatbot interactions, built using Compose for Desktop, Ktor, and Exposed ORM. The project follows a layered architecture pattern distributed across three Gradle modules: `common`, `server`, and `app`.

## Technology Stack

- **Language**: Kotlin 2.1.0
- **UI Framework**: Compose for Desktop 1.8.1
- **HTTP Server/Client**: Ktor 3.1.0
- **Database ORM**: Exposed 0.58.0
- **Dependency Injection**: Koin 4.0.2
- **Logging**: Log4j 2.21.0
- **Functional Programming**: Arrow 2.1.2
- **Database**: SQLite (local storage)

## Module Structure

### Root Project Structure
```
chatbot/
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Module definitions
├── gradle.properties             # Global Gradle properties
├── gradle/
│   ├── libs.versions.toml        # Version catalog
│   └── wrapper/                  # Gradle wrapper
├── build-logic/                  # Custom Gradle convention plugins
│   └── src/main/kotlin/
│       └── CommonModuleConventionPlugin.kt
├── docs/                         # Project documentation
├── temp/                         # Temporary files
├── common/                       # Shared models and utilities
├── server/                       # Backend logic and services
└── app/                          # Desktop UI application
```

## Detailed Module Breakdown

### 1. Common Module (`common/`)

**Purpose**: Shared data models (DTOs) and utilities used by both frontend and backend modules.

**Package Structure**:
```
common/src/main/kotlin/eu/torvian/chatbot/common/
├── api/resources/                # API resource definitions (for Ktor Resources plugin)
├── models/                       # Shared data models (DTOs)
│   ├── AddModelRequest.kt        # Request DTO for adding LLM models
│   ├── AddModelSettingsRequest.kt # Request DTO for adding model settings
│   ├── AddProviderRequest.kt     # Request DTO for adding LLM providers
│   ├── ApiKeyStatusResponse.kt   # Response DTO for API key status
│   ├── AssignSessionToGroupRequest.kt # Request DTO for assigning session to group
│   ├── ChatGroup.kt              # Chat group data model
│   ├── ChatMessage.kt            # Chat message with threading support
│   ├── ChatSession.kt            # Chat session data model
│   ├── ChatSessionSummary.kt     # Session summary for lists
│   ├── CreateGroupRequest.kt     # Group creation request DTO
│   ├── CreateSessionRequest.kt   # Session creation request DTO
│   ├── LLMModel.kt               # LLM model configuration
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
│   └── UpdateSessionSettingsRequest.kt # Request DTO for updating session settings
└── misc/                         # Miscellaneous utilities
    └── di/                       # Dependency injection abstractions
        ├── DIContainer.kt        # Framework-agnostic DI interface
        └── KoinDIContainer.kt    # Koin-specific DI implementation
```

**Key Features**:
- All data models are serializable with kotlinx.serialization
- Support for message threading and chat grouping
- Framework-agnostic dependency injection abstractions
- Shared between frontend and backend for API communication

### 2. Server Module (`server/`)

**Purpose**: Backend logic including HTTP server, business services, data access, and external integrations.

**Package Structure**:
```
server/src/main/kotlin/eu/torvian/chatbot/server/
├── data/                         # Data access layer
│   ├── dao/                      # Data Access Objects (interfaces)
│   │   ├── ApiSecretDao.kt       # API secret management interface
│   │   ├── GroupDao.kt           # Group operations interface
│   │   ├── MessageDao.kt         # Message operations interface
│   │   ├── ModelDao.kt           # Model management interface
│   │   ├── SessionDao.kt         # Session operations interface
│   │   ├── SettingsDao.kt        # Settings management interface
│   │   ├── error/                # Domain-specific error types
│   │   └── exposed/              # Exposed ORM implementations
│   ├── entities/                 # Database entity mappings
│   │   ├── ApiSecretEntity.kt    # API secret entity
│   │   ├── ChatSessionEntity.kt  # Chat session entity
│   │   └── SessionCurrentLeafEntity.kt # Session current leaf entity
│   └── tables/                   # Exposed table definitions
│       ├── ApiSecretTable.kt     # API secrets table
│       ├── AssistantMessageTable.kt # Assistant messages table
│       ├── ChatGroupTable.kt     # Chat groups table
│       ├── ChatMessageTable.kt   # Chat messages table
│       ├── ChatSessionTable.kt   # Chat sessions table
│       ├── LLMModelTable.kt      # LLM models table
│       ├── LLMProviderTable.kt   # LLM providers table
│       ├── ModelSettingsTable.kt # Model settings table
│       ├── SessionCurrentLeafTable.kt # Session current leaf tracking
│       └── mappers/              # Entity mapping utilities
├── domain/                       # Domain models and configuration
│   ├── config/                   # Configuration classes
│   │   └── DatabaseConfig.kt     # Database configuration
│   └── security/                 # Security-related classes
│       ├── EncryptedSecret.kt    # Encrypted secret data model
│       └── EncryptionConfig.kt   # Encryption configuration
├── koin/                         # Dependency injection modules
│   ├── configModule.kt           # Configuration DI module
│   ├── daoModule.kt              # DAO implementations DI module
│   ├── databaseModule.kt         # Database connection DI module
│   ├── miscModule.kt             # Miscellaneous services DI module
│   └── serviceModule.kt          # Service implementations DI module
├── ktor/                         # Ktor server setup
│   ├── configureKtor.kt          # Ktor server plugin configuration
│   └── routes/                   # Ktor API routes
│       ├── ApiRoutesKtor.kt      # Ktor route configuration using type-safe Resources plugin
│       ├── configureGroupRoutes.kt
│       ├── configureMessageRoutes.kt
│       ├── configureModelRoutes.kt
│       ├── configureProviderRoutes.kt
│       ├── configureSessionRoutes.kt
│       └── configureSettingsRoutes.kt
├── main/
│   ├── DataManager.kt            # Interface for managing database schema
│   ├── ExposedDataManager.kt     # Exposed data manager implementation
│   ├── mainModule.kt             # Koin module for main application setup
│   └── ServerMain.kt             # Main application entry point
├── service/                      # Business logic services
│   ├── core/                     # Core services
│   │   ├── GroupService.kt       # Group management service interface
│   │   ├── LLMModelService.kt    # LLM Model management service interface
│   │   ├── LLMProviderService.kt # LLM Provider management service interface
│   │   ├── MessageService.kt     # Message handling service interface
│   │   ├── ModelSettingsService.kt # Model Settings management service interface
│   │   ├── SessionService.kt     # Session management service interface
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
│   │   └── strategy/             # LLM completion strategy implementations
│   │       ├── OpenAiApiModels.kt # OpenAI API models (DTOs)
│   │       └── OpenAIChatStrategy.kt # OpenAI chat completion strategy
│   └── security/                 # Security services
│       ├── AESCryptoProvider.kt  # AES encryption provider
│       ├── CredentialManager.kt  # Credential management interface
│       ├── CryptoProvider.kt     # Crypto provider interface
│       ├── DbEncryptedCredentialManager.kt # Database-backed credential manager
│       ├── EncryptionService.kt  # Encryption service interface
│       └── error/                # Domain-specific error types
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
│       ├── ApiSecretDaoExposedTest.kt
│       ├── GroupDaoExposedTest.kt
│       ├── LLMProviderDaoExposedTest.kt
│       ├── MessageDaoExposedTest.kt
│       ├── ModelDaoExposedTest.kt
│       ├── SessionDaoExposedTest.kt
│       └── SettingsDaoExposedTest.kt
├── ktor/routes/                  # Ktor route tests
│   ├── GroupRoutesTest.kt
│   ├── MessageRoutesTest.kt
│   ├── ModelRoutesTest.kt
│   ├── ProviderRoutesTest.kt
│   ├── SessionRoutesTest.kt
│   └── SettingsRoutesTest.kt
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
│   │       └── OpenAIChatStrategyTest.kt
│   └── security/                 # Security service tests
│       ├── AESCryptoProviderTest.kt
│       ├── DbEncryptedCredentialManagerTest.kt
│       └── EncryptionServiceTest.kt
└── testutils/                    # Test utilities
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

**Key Features**:
- Layered architecture with clear separation of concerns
- Repository pattern with DAO interfaces and Exposed implementations
- Comprehensive transaction management
- Extensive test coverage with test utilities
- Koin dependency injection throughout

### 3. App Module (`app/`)

**Purpose**: Desktop application frontend built with Compose for Desktop.

**Package Structure**:
```
app/src/main/kotlin/eu/torvian/chatbot/app/
├── AppMain.kt        <- Application entry point, setup (Ktor Server start, UI launch, DI)
│
├── ui/            <- UI Layer (Compose for Desktop) - Renders threads and grouped sessions
│   ├── AppLayout.kt
│   ├── ChatArea.kt
│   ├── SessionListPanel.kt
│   ├── InputArea.kt
│   ├── SettingsScreen.kt
│   ├── ... other UI components ...
│   └── state/           <- UI State Management (e.g., ChatState, SessionListState ViewModel) - Handles threaded and grouped data for display
│       ├── ChatState.kt <- Depends on eu.torvian.chatbot.app.api.client.ChatApi
│       └── SessionListState.kt <- Depends on eu.torvian.chatbot.app.api.client.ChatApi, eu.torvian.chatbot.app.api.client.GroupApi
└── api/
    └── client/        <- Frontend API Client Layer - Translates UI actions to API calls for chat, models, settings, and groups
        ├── ChatApi.kt <- Interface (Consumed by eu.torvian.chatbot.app.ui.state.ChatState/SessionListState)
        ├── GroupApi.kt <- Interface (Consumed by eu.torvian.chatbot.app.ui.state.SessionListState or similar)
        ├── KtorChatApiClient.kt <- Implementation (Uses Ktor Client to talk to localhost)
        └── KtorGroupApiClient.kt <- Implementation (Uses Ktor Client to talk to localhost)

```

**Key Features**:
- Compose for Desktop UI framework
- Simple application structure (currently minimal implementation)
- Entry point for the desktop application

## Architecture Overview

### Module Dependencies
- **app** depends on **common** (for shared models)
- **server** depends on **common** (for shared models)
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

### Key Dependencies
- **Ktor**: HTTP server and client functionality
- **Exposed**: Type-safe SQL framework for Kotlin
- **Compose**: Modern UI toolkit for desktop applications
- **Koin**: Lightweight dependency injection framework
- **kotlinx.serialization**: Kotlin serialization library
- **SQLite**: Embedded database for local storage

This structure provides a clean, maintainable, and testable architecture suitable for a desktop AI chatbot application.