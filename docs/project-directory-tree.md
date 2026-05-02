# Project Directory Tree
This is the directory structure of the project, showing the organization of the `server`, `worker`, `app`, and `common` modules, along with their respective source code and configuration files. Each module is structured to separate concerns such as configuration, data access, domain logic, and API routes, following best practices for maintainability and scalability. The `server` module contains the core backend logic, including the Ktor server setup, database interactions using Exposed ORM, and service implementations for handling business logic and API requests. The `worker` module is designed for executing tasks related to the Model Control Plane (MCP), while the `app` module focuses on the frontend application using Compose Multiplatform. The `common` module contains shared code that can be used across both the server and app modules, such as data models, utility functions, and shared configurations. This modular structure allows for clear separation of concerns and facilitates easier development and maintenance of the overall application.

## Document index
- [Server Module](#server-module)
- [Worker Module](#worker-module)
- [App Module](#app-module)
- [Common Module](#common-module)

## Server Module

```text
.
├── server/
│   ├── dev-config-sample/
│   │   ├── application.json
│   │   ├── env-mapping.json
│   │   ├── secrets.json
│   │   └── setup.json
│   └── src/
│       ├── main/
│       │   ├── dist/config/
│       │   │   ├── application_example.json
│       │   │   ├── secrets_example.json
│       │   │   └── setup_example.json
│       │   ├── kotlin/eu/torvian/chatbot/server/
│       │   │   ├── config/
│       │   │   │   ├── AppConfiguration.kt
│       │   │   │   ├── ConfigAssembler.kt
│       │   │   │   ├── ConfigDtos.kt
│       │   │   │   ├── ConfigError.kt
│       │   │   │   ├── ServerConfigLoader.kt
│       │   │   │   └── SslSanValidation.kt
│       │   │   ├── data/
│       │   │   │   ├── dao/
│       │   │   │   │   ├── error/
│       │   │   │   │   │   ├── usergroup/
│       │   │   │   │   │   │   ├── AddUserToGroupError.kt
│       │   │   │   │   │   │   ├── DeleteGroupError.kt
│       │   │   │   │   │   │   ├── GetGroupByIdError.kt
│       │   │   │   │   │   │   ├── GetGroupByNameError.kt
│       │   │   │   │   │   │   ├── InsertGroupError.kt
│       │   │   │   │   │   │   ├── RemoveUserFromGroupError.kt
│       │   │   │   │   │   │   └── UpdateGroupError.kt
│       │   │   │   │   │   ├── AccessErrors.kt
│       │   │   │   │   │   ├── ApiSecretError.kt
│       │   │   │   │   │   ├── GroupError.kt
│       │   │   │   │   │   ├── InsertMessageError.kt
│       │   │   │   │   │   ├── InsertModelError.kt
│       │   │   │   │   │   ├── LLMProviderError.kt
│       │   │   │   │   │   ├── LocalMCPServerError.kt
│       │   │   │   │   │   ├── LocalMCPToolDefinitionError.kt
│       │   │   │   │   │   ├── MessageError.kt
│       │   │   │   │   │   ├── ModelError.kt
│       │   │   │   │   │   ├── OwnershipErrors.kt
│       │   │   │   │   │   ├── PermissionError.kt
│       │   │   │   │   │   ├── RoleError.kt
│       │   │   │   │   │   ├── RolePermissionError.kt
│       │   │   │   │   │   ├── SessionError.kt
│       │   │   │   │   │   ├── SessionToolConfigError.kt
│       │   │   │   │   │   ├── SettingsError.kt
│       │   │   │   │   │   ├── ToolCallError.kt
│       │   │   │   │   │   ├── ToolDefinitionError.kt
│       │   │   │   │   │   ├── UpdateModelError.kt
│       │   │   │   │   │   ├── UserError.kt
│       │   │   │   │   │   ├── UserRoleAssignmentError.kt
│       │   │   │   │   │   ├── UserSessionError.kt
│       │   │   │   │   │   ├── UserToolApprovalPreferenceError.kt
│       │   │   │   │   │   └── WorkerError.kt
│       │   │   │   │   ├── exposed/
│       │   │   │   │   │   ├── ApiSecretDaoExposed.kt
│       │   │   │   │   │   ├── ExposedExtensions.kt
│       │   │   │   │   │   ├── GroupDaoExposed.kt
│       │   │   │   │   │   ├── GroupOwnershipDaoExposed.kt
│       │   │   │   │   │   ├── LLMProviderDaoExposed.kt
│       │   │   │   │   │   ├── LocalMCPServerDaoExposed.kt
│       │   │   │   │   │   ├── LocalMCPToolDefinitionDaoExposed.kt
│       │   │   │   │   │   ├── MessageDaoExposed.kt
│       │   │   │   │   │   ├── ModelAccessDaoExposed.kt
│       │   │   │   │   │   ├── ModelDaoExposed.kt
│       │   │   │   │   │   ├── ModelOwnershipDaoExposed.kt
│       │   │   │   │   │   ├── PermissionDaoExposed.kt
│       │   │   │   │   │   ├── ProviderAccessDaoExposed.kt
│       │   │   │   │   │   ├── ProviderOwnershipDaoExposed.kt
│       │   │   │   │   │   ├── RoleDaoExposed.kt
│       │   │   │   │   │   ├── RolePermissionDaoExposed.kt
│       │   │   │   │   │   ├── SessionDaoExposed.kt
│       │   │   │   │   │   ├── SessionOwnershipDaoExposed.kt
│       │   │   │   │   │   ├── SessionToolConfigDaoExposed.kt
│       │   │   │   │   │   ├── SettingsAccessDaoExposed.kt
│       │   │   │   │   │   ├── SettingsDaoExposed.kt
│       │   │   │   │   │   ├── SettingsOwnershipDaoExposed.kt
│       │   │   │   │   │   ├── ToolCallDaoExposed.kt
│       │   │   │   │   │   ├── ToolDefinitionDaoExposed.kt
│       │   │   │   │   │   ├── UserDaoExposed.kt
│       │   │   │   │   │   ├── UserGroupDaoExposed.kt
│       │   │   │   │   │   ├── UserRoleAssignmentDaoExposed.kt
│       │   │   │   │   │   ├── UserSessionDaoExposed.kt
│       │   │   │   │   │   ├── UserToolApprovalPreferenceDaoExposed.kt
│       │   │   │   │   │   └── WorkerDaoExposed.kt
│       │   │   │   │   ├── ApiSecretDao.kt
│       │   │   │   │   ├── GroupDao.kt
│       │   │   │   │   ├── GroupOwnershipDao.kt
│       │   │   │   │   ├── LLMProviderDao.kt
│       │   │   │   │   ├── LocalMCPServerDao.kt
│       │   │   │   │   ├── LocalMCPToolDefinitionDao.kt
│       │   │   │   │   ├── MessageDao.kt
│       │   │   │   │   ├── ModelAccessDao.kt
│       │   │   │   │   ├── ModelDao.kt
│       │   │   │   │   ├── ModelOwnershipDao.kt
│       │   │   │   │   ├── PermissionDao.kt
│       │   │   │   │   ├── ProviderAccessDao.kt
│       │   │   │   │   ├── ProviderOwnershipDao.kt
│       │   │   │   │   ├── RoleDao.kt
│       │   │   │   │   ├── RolePermissionDao.kt
│       │   │   │   │   ├── SessionDao.kt
│       │   │   │   │   ├── SessionOwnershipDao.kt
│       │   │   │   │   ├── SessionToolConfigDao.kt
│       │   │   │   │   ├── SettingsAccessDao.kt
│       │   │   │   │   ├── SettingsDao.kt
│       │   │   │   │   ├── SettingsOwnershipDao.kt
│       │   │   │   │   ├── ToolCallDao.kt
│       │   │   │   │   ├── ToolDefinitionDao.kt
│       │   │   │   │   ├── UserDao.kt
│       │   │   │   │   ├── UserGroupDao.kt
│       │   │   │   │   ├── UserRoleAssignmentDao.kt
│       │   │   │   │   ├── UserSessionDao.kt
│       │   │   │   │   ├── UserToolApprovalPreferenceDao.kt
│       │   │   │   │   └── WorkerDao.kt
│       │   │   │   ├── entities/
│       │   │   │   │   ├── mappers/
│       │   │   │   │   │   ├── toPermission.kt
│       │   │   │   │   │   ├── toRole.kt
│       │   │   │   │   │   ├── toUser.kt
│       │   │   │   │   │   ├── toUserGroup.kt
│       │   │   │   │   │   └── toWorkerDto.kt
│       │   │   │   │   ├── ApiSecretEntity.kt
│       │   │   │   │   ├── ChatSessionEntity.kt
│       │   │   │   │   ├── LocalMCPServerEntity.kt
│       │   │   │   │   ├── PermissionEntity.kt
│       │   │   │   │   ├── RoleEntity.kt
│       │   │   │   │   ├── RolePermissionEntity.kt
│       │   │   │   │   ├── SessionCurrentLeafEntity.kt
│       │   │   │   │   ├── UserEntity.kt
│       │   │   │   │   ├── UserGroupEntity.kt
│       │   │   │   │   ├── UserRoleAssignmentEntity.kt
│       │   │   │   │   ├── UserSessionEntity.kt
│       │   │   │   │   ├── WorkerAuthChallengeEntity.kt
│       │   │   │   │   └── WorkerEntity.kt
│       │   │   │   ├── tables/
│       │   │   │   │   ├── mappers/
│       │   │   │   │   │   ├── toApiSecretEntity.kt
│       │   │   │   │   │   ├── toAssistantMessage.kt
│       │   │   │   │   │   ├── toChatGroup.kt
│       │   │   │   │   │   ├── toChatSessionEntity.kt
│       │   │   │   │   │   ├── toLLMModel.kt
│       │   │   │   │   │   ├── toLLMProvider.kt
│       │   │   │   │   │   ├── toLocalMCPServerEntity.kt
│       │   │   │   │   │   ├── toLocalMCPToolDefinition.kt
│       │   │   │   │   │   ├── toMiscToolDefinition.kt
│       │   │   │   │   │   ├── toModelSettings.kt
│       │   │   │   │   │   ├── toPermissionEntity.kt
│       │   │   │   │   │   ├── toRoleEntity.kt
│       │   │   │   │   │   ├── toRolePermissionEntity.kt
│       │   │   │   │   │   ├── toSessionCurrentLeafEntity.kt
│       │   │   │   │   │   ├── toToolCall.kt
│       │   │   │   │   │   ├── toToolDefinition.kt
│       │   │   │   │   │   ├── toUserEntity.kt
│       │   │   │   │   │   ├── toUserGroupEntity.kt
│       │   │   │   │   │   ├── toUserMessage.kt
│       │   │   │   │   │   ├── toUserRoleAssignmentEntity.kt
│       │   │   │   │   │   ├── toUserSessionEntity.kt
│       │   │   │   │   │   ├── toWorkerAuthChallengeEntity.kt
│       │   │   │   │   │   └── toWorkerEntity.kt
│       │   │   │   │   ├── ApiSecretOwnersTable.kt
│       │   │   │   │   ├── ApiSecretTable.kt
│       │   │   │   │   ├── AssistantMessageTable.kt
│       │   │   │   │   ├── ChatGroupOwnersTable.kt
│       │   │   │   │   ├── ChatGroupTable.kt
│       │   │   │   │   ├── ChatMessageTable.kt
│       │   │   │   │   ├── ChatSessionOwnersTable.kt
│       │   │   │   │   ├── ChatSessionTable.kt
│       │   │   │   │   ├── LLMModelAccessTable.kt
│       │   │   │   │   ├── LLMModelOwnersTable.kt
│       │   │   │   │   ├── LLMModelTable.kt
│       │   │   │   │   ├── LLMProviderAccessTable.kt
│       │   │   │   │   ├── LLMProviderOwnersTable.kt
│       │   │   │   │   ├── LLMProviderTable.kt
│       │   │   │   │   ├── LocalMCPServerTable.kt
│       │   │   │   │   ├── LocalMCPToolDefinitionTable.kt
│       │   │   │   │   ├── ModelSettingsAccessTable.kt
│       │   │   │   │   ├── ModelSettingsOwnersTable.kt
│       │   │   │   │   ├── ModelSettingsTable.kt
│       │   │   │   │   ├── PermissionsTable.kt
│       │   │   │   │   ├── RolePermissionsTable.kt
│       │   │   │   │   ├── RolesTable.kt
│       │   │   │   │   ├── SessionCurrentLeafTable.kt
│       │   │   │   │   ├── SessionToolConfigTable.kt
│       │   │   │   │   ├── ToolCallTable.kt
│       │   │   │   │   ├── ToolDefinitionTable.kt
│       │   │   │   │   ├── UserGroupMembershipsTable.kt
│       │   │   │   │   ├── UserGroupsTable.kt
│       │   │   │   │   ├── UserRoleAssignmentsTable.kt
│       │   │   │   │   ├── UserSessionsTable.kt
│       │   │   │   │   ├── UsersTable.kt
│       │   │   │   │   ├── UserToolApprovalPreferencesTable.kt
│       │   │   │   │   ├── WorkerAuthChallengesTable.kt
│       │   │   │   │   └── WorkersTable.kt
│       │   │   │   └── ModelSettingsMapper.kt
│       │   │   ├── domain/
│       │   │   │   ├── config/
│       │   │   │   │   ├── CorsConfig.kt
│       │   │   │   │   ├── DatabaseConfig.kt
│       │   │   │   │   ├── NetworkConfig.kt
│       │   │   │   │   ├── ServerConnectorType.kt
│       │   │   │   │   ├── SslConfig.kt
│       │   │   │   │   └── StorageConfig.kt
│       │   │   │   └── security/
│       │   │   │       ├── mappers/
│       │   │   │       │   └── toLoginResponse.kt
│       │   │   │       ├── AuthSchemes.kt
│       │   │   │       ├── JwtConfig.kt
│       │   │   │       ├── LoginResult.kt
│       │   │   │       ├── UserContext.kt
│       │   │   │       └── WorkerContext.kt
│       │   │   ├── koin/
│       │   │   │   ├── configModule.kt
│       │   │   │   ├── daoModule.kt
│       │   │   │   ├── databaseModule.kt
│       │   │   │   ├── miscModule.kt
│       │   │   │   └── serviceModule.kt
│       │   │   ├── ktor/
│       │   │   │   ├── auth/
│       │   │   │   │   ├── AuthUtils.kt
│       │   │   │   │   └── WebSocketAuthHeaderExtractor.kt
│       │   │   │   ├── mappers/
│       │   │   │   │   ├── toChatEvent.kt
│       │   │   │   │   └── toChatStreamEvent.kt
│       │   │   │   ├── routes/
│       │   │   │   │   ├── ApiRoutesKtor.kt
│       │   │   │   │   ├── AuthorizationHelpers.kt
│       │   │   │   │   ├── configureAuthRoutes.kt
│       │   │   │   │   ├── configureGroupRoutes.kt
│       │   │   │   │   ├── configureLocalMCPServerRoutes.kt
│       │   │   │   │   ├── configureLocalMCPToolRoutes.kt
│       │   │   │   │   ├── configureMessageRoutes.kt
│       │   │   │   │   ├── configureModelRoutes.kt
│       │   │   │   │   ├── configureProviderRoutes.kt
│       │   │   │   │   ├── configureRoleRoutes.kt
│       │   │   │   │   ├── configureSessionRoutes.kt
│       │   │   │   │   ├── configureSettingsRoutes.kt
│       │   │   │   │   ├── configureToolRoutes.kt
│       │   │   │   │   ├── configureUserGroupRoutes.kt
│       │   │   │   │   ├── configureUserRoutes.kt
│       │   │   │   │   ├── configureWorkerRoutes.kt
│       │   │   │   │   ├── configureWorkerWebSocketRoutes.kt
│       │   │   │   │   ├── ResourceExtensions.kt
│       │   │   │   │   └── respondEither.kt
│       │   │   │   └── configureKtor.kt
│       │   │   ├── main/
│       │   │   │   ├── chatBotServerModule.kt
│       │   │   │   ├── DatabaseMigrator.kt
│       │   │   │   ├── mainModule.kt
│       │   │   │   ├── ServerControlService.kt
│       │   │   │   ├── ServerControlServiceImpl.kt
│       │   │   │   ├── ServerInstanceInfo.kt
│       │   │   │   ├── ServerMain.kt
│       │   │   │   └── ServerStatus.kt
│       │   │   ├── service/
│       │   │   │   ├── core/
│       │   │   │   │   ├── error/
│       │   │   │   │   │   ├── access/
│       │   │   │   │   │   │   ├── AccessManagementError.kt
│       │   │   │   │   │   │   └── ConvenienceErrors.kt
│       │   │   │   │   │   ├── auth/
│       │   │   │   │   │   │   ├── AssignRoleError.kt
│       │   │   │   │   │   │   ├── ChangePasswordError.kt
│       │   │   │   │   │   │   ├── CreateRoleError.kt
│       │   │   │   │   │   │   ├── DeleteRoleError.kt
│       │   │   │   │   │   │   ├── DeleteUserError.kt
│       │   │   │   │   │   │   ├── RegisterUserError.kt
│       │   │   │   │   │   │   ├── RevokeRoleError.kt
│       │   │   │   │   │   │   ├── RoleNotFoundError.kt
│       │   │   │   │   │   │   ├── UpdateRoleError.kt
│       │   │   │   │   │   │   ├── UpdateUserError.kt
│       │   │   │   │   │   │   └── UserNotFoundError.kt
│       │   │   │   │   │   ├── group/
│       │   │   │   │   │   │   ├── CreateGroupError.kt
│       │   │   │   │   │   │   ├── DeleteGroupError.kt
│       │   │   │   │   │   │   └── RenameGroupError.kt
│       │   │   │   │   │   ├── mcp/
│       │   │   │   │   │   │   ├── LocalMCPServerServiceErrorExtensions.kt
│       │   │   │   │   │   │   ├── LocalMCPServerServiceErrors.kt
│       │   │   │   │   │   │   ├── LocalMCPToolDefinitionServiceErrorExtensions.kt
│       │   │   │   │   │   │   └── LocalMCPToolDefinitionServiceErrors.kt
│       │   │   │   │   │   ├── message/
│       │   │   │   │   │   │   ├── DeleteMessageError.kt
│       │   │   │   │   │   │   ├── GetMessageError.kt
│       │   │   │   │   │   │   ├── InsertMessageError.kt
│       │   │   │   │   │   │   ├── ProcessNewMessageError.kt
│       │   │   │   │   │   │   ├── UpdateMessageContentError.kt
│       │   │   │   │   │   │   └── ValidateNewMessageError.kt
│       │   │   │   │   │   ├── model/
│       │   │   │   │   │   │   ├── AddModelError.kt
│       │   │   │   │   │   │   ├── DeleteModelError.kt
│       │   │   │   │   │   │   ├── GetModelError.kt
│       │   │   │   │   │   │   ├── ModelErrorExtensions.kt
│       │   │   │   │   │   │   └── UpdateModelError.kt
│       │   │   │   │   │   ├── provider/
│       │   │   │   │   │   │   ├── AddProviderError.kt
│       │   │   │   │   │   │   ├── DeleteProviderError.kt
│       │   │   │   │   │   │   ├── DiscoverProviderModelsError.kt
│       │   │   │   │   │   │   ├── GetProviderError.kt
│       │   │   │   │   │   │   ├── ProviderErrorExtensions.kt
│       │   │   │   │   │   │   ├── TestProviderConnectionError.kt
│       │   │   │   │   │   │   ├── UpdateProviderCredentialError.kt
│       │   │   │   │   │   │   └── UpdateProviderError.kt
│       │   │   │   │   │   ├── session/
│       │   │   │   │   │   │   ├── CloneSessionError.kt
│       │   │   │   │   │   │   ├── CreateSessionError.kt
│       │   │   │   │   │   │   ├── DeleteSessionError.kt
│       │   │   │   │   │   │   ├── GetSessionDetailsError.kt
│       │   │   │   │   │   │   ├── UpdateSessionCurrentModelAndSettingsIdError.kt
│       │   │   │   │   │   │   ├── UpdateSessionCurrentModelIdError.kt
│       │   │   │   │   │   │   ├── UpdateSessionCurrentSettingsIdError.kt
│       │   │   │   │   │   │   ├── UpdateSessionGroupIdError.kt
│       │   │   │   │   │   │   ├── UpdateSessionLeafMessageIdError.kt
│       │   │   │   │   │   │   └── UpdateSessionNameError.kt
│       │   │   │   │   │   ├── settings/
│       │   │   │   │   │   │   ├── AddSettingsError.kt
│       │   │   │   │   │   │   ├── DeleteSettingsError.kt
│       │   │   │   │   │   │   ├── GetSettingsByIdError.kt
│       │   │   │   │   │   │   ├── SettingsErrorExtensions.kt
│       │   │   │   │   │   │   └── UpdateSettingsError.kt
│       │   │   │   │   │   ├── tool/
│       │   │   │   │   │   │   ├── ToolServiceErrorExtensions.kt
│       │   │   │   │   │   │   └── ToolServiceErrors.kt
│       │   │   │   │   │   ├── usergroup/
│       │   │   │   │   │   │   ├── AddUserToGroupError.kt
│       │   │   │   │   │   │   ├── CreateGroupError.kt
│       │   │   │   │   │   │   ├── DeleteGroupError.kt
│       │   │   │   │   │   │   ├── GetGroupByIdError.kt
│       │   │   │   │   │   │   ├── GetGroupByNameError.kt
│       │   │   │   │   │   │   ├── RemoveUserFromGroupError.kt
│       │   │   │   │   │   │   ├── UpdateGroupError.kt
│       │   │   │   │   │   │   └── UserGroupErrorExtensions.kt
│       │   │   │   │   │   └── worker/
│       │   │   │   │   │       └── WorkerServiceErrors.kt
│       │   │   │   │   ├── impl/
│       │   │   │   │   │   ├── ChatServiceImpl.kt
│       │   │   │   │   │   ├── GroupServiceImpl.kt
│       │   │   │   │   │   ├── LLMModelServiceImpl.kt
│       │   │   │   │   │   ├── LLMProviderServiceImpl.kt
│       │   │   │   │   │   ├── LocalMCPServerServiceImpl.kt
│       │   │   │   │   │   ├── LocalMCPToolDefinitionServiceImpl.kt
│       │   │   │   │   │   ├── MessageServiceImpl.kt
│       │   │   │   │   │   ├── ModelSettingsServiceImpl.kt
│       │   │   │   │   │   ├── RoleServiceImpl.kt
│       │   │   │   │   │   ├── SessionServiceImpl.kt
│       │   │   │   │   │   ├── ToolCallServiceImpl.kt
│       │   │   │   │   │   ├── ToolServiceImpl.kt
│       │   │   │   │   │   ├── UserGroupServiceImpl.kt
│       │   │   │   │   │   ├── UserServiceImpl.kt
│       │   │   │   │   │   └── WorkerServiceImpl.kt
│       │   │   │   │   ├── ChatService.kt
│       │   │   │   │   ├── GroupService.kt
│       │   │   │   │   ├── LLMConfig.kt
│       │   │   │   │   ├── LLMModelService.kt
│       │   │   │   │   ├── LLMProviderService.kt
│       │   │   │   │   ├── LocalMCPServerService.kt
│       │   │   │   │   ├── LocalMCPToolDefinitionService.kt
│       │   │   │   │   ├── MessageEvent.kt
│       │   │   │   │   ├── MessageService.kt
│       │   │   │   │   ├── MessageStreamEvent.kt
│       │   │   │   │   ├── ModelSettingsService.kt
│       │   │   │   │   ├── RoleService.kt
│       │   │   │   │   ├── SessionService.kt
│       │   │   │   │   ├── ToolCallService.kt
│       │   │   │   │   ├── ToolService.kt
│       │   │   │   │   ├── UserGroupService.kt
│       │   │   │   │   ├── UserService.kt
│       │   │   │   │   └── WorkerService.kt
│       │   │   │   ├── llm/
│       │   │   │   │   ├── discovery/
│       │   │   │   │   │   ├── OllamaModelDiscoveryStrategy.kt
│       │   │   │   │   │   ├── OpenAIModelDiscoveryStrategy.kt
│       │   │   │   │   │   └── OpenRouterModelDiscoveryStrategy.kt
│       │   │   │   │   ├── strategy/
│       │   │   │   │   │   ├── OllamaApiModels.kt
│       │   │   │   │   │   ├── OllamaChatStrategy.kt
│       │   │   │   │   │   ├── OpenAiApiModels.kt
│       │   │   │   │   │   └── OpenAIChatStrategy.kt
│       │   │   │   │   ├── ApiRequestConfig.kt
│       │   │   │   │   ├── ChatCompletionStrategy.kt
│       │   │   │   │   ├── GenericContentType.kt
│       │   │   │   │   ├── GenericHttpMethod.kt
│       │   │   │   │   ├── LLMApiClient.kt
│       │   │   │   │   ├── LLMApiClientKtor.kt
│       │   │   │   │   ├── LLMCompletionError.kt
│       │   │   │   │   ├── LLMCompletionResult.kt
│       │   │   │   │   ├── LLMStreamChunk.kt
│       │   │   │   │   ├── ModelDiscoveryError.kt
│       │   │   │   │   ├── ModelDiscoveryResult.kt
│       │   │   │   │   ├── ModelDiscoveryStrategy.kt
│       │   │   │   │   └── RawChatMessage.kt
│       │   │   │   ├── mcp/
│       │   │   │   │   ├── LocalMCPExecutor.kt
│       │   │   │   │   ├── LocalMCPExecutorError.kt
│       │   │   │   │   └── LocalMCPExecutorEvent.kt
│       │   │   │   ├── security/
│       │   │   │   │   ├── authorizer/
│       │   │   │   │   │   ├── GroupResourceAuthorizer.kt
│       │   │   │   │   │   ├── ModelResourceAuthorizer.kt
│       │   │   │   │   │   ├── ProviderResourceAuthorizer.kt
│       │   │   │   │   │   ├── ResourceAuthorizer.kt
│       │   │   │   │   │   ├── ResourceAuthorizerError.kt
│       │   │   │   │   │   ├── SessionResourceAuthorizer.kt
│       │   │   │   │   │   └── SettingsResourceAuthorizer.kt
│       │   │   │   │   ├── error/
│       │   │   │   │   │   ├── AuthorizationError.kt
│       │   │   │   │   │   ├── CredentialError.kt
│       │   │   │   │   │   ├── LoginError.kt
│       │   │   │   │   │   ├── LogoutError.kt
│       │   │   │   │   │   ├── RefreshTokenError.kt
│       │   │   │   │   │   ├── ResourceAuthorizationError.kt
│       │   │   │   │   │   └── TokenValidationError.kt
│       │   │   │   │   ├── AuthenticationService.kt
│       │   │   │   │   ├── AuthenticationServiceImpl.kt
│       │   │   │   │   ├── AuthorizationService.kt
│       │   │   │   │   ├── AuthorizationServiceImpl.kt
│       │   │   │   │   ├── BCryptPasswordService.kt
│       │   │   │   │   ├── CertificateManager.kt
│       │   │   │   │   ├── CertificateService.kt
│       │   │   │   │   ├── CredentialManager.kt
│       │   │   │   │   ├── DbEncryptedCredentialManager.kt
│       │   │   │   │   ├── DefaultCertificateManager.kt
│       │   │   │   │   ├── DefaultCertificateService.kt
│       │   │   │   │   ├── PasswordService.kt
│       │   │   │   │   └── ResourceType.kt
│       │   │   │   ├── setup/
│       │   │   │   │   ├── DataInitializer.kt
│       │   │   │   │   ├── InitializationCoordinator.kt
│       │   │   │   │   ├── ToolDefinitionInitializer.kt
│       │   │   │   │   └── UserAccountInitializer.kt
│       │   │   │   └── tool/
│       │   │   │       ├── error/
│       │   │   │       │   └── ToolExecutionError.kt
│       │   │   │       ├── impl/
│       │   │   │       │   ├── WeatherToolExecutor.kt
│       │   │   │       │   └── WebSearchToolExecutor.kt
│       │   │   │       ├── ToolExecutor.kt
│       │   │   │       └── ToolExecutorFactory.kt
│       │   │   ├── utils/
│       │   │   │   ├── misc/
│       │   │   │   │   └── KtorDIExtensions.kt
│       │   │   │   └── transactions/
│       │   │   │       └── ExposedTransactionScope.kt
│       │   │   └── worker/
│       │   │       ├── command/
│       │   │       │   ├── pending/
│       │   │       │   │   ├── InMemoryPendingWorkerCommandRegistry.kt
│       │   │       │   │   ├── PendingWorkerCommand.kt
│       │   │       │   │   └── PendingWorkerCommandRegistry.kt
│       │   │       │   ├── DefaultWorkerCommandDispatchService.kt
│       │   │       │   ├── WorkerCommandDispatchError.kt
│       │   │       │   ├── WorkerCommandDispatchService.kt
│       │   │       │   └── WorkerCommandDispatchSuccess.kt
│       │   │       ├── mcp/
│       │   │       │   ├── configsync/
│       │   │       │   │   ├── DefaultLocalMCPServerConfigSyncService.kt
│       │   │       │   │   └── LocalMCPServerConfigSyncService.kt
│       │   │       │   ├── runtimecontrol/
│       │   │       │   │   ├── DefaultLocalMCPRuntimeCommandDispatchService.kt
│       │   │       │   │   ├── DefaultLocalMCPRuntimeControlService.kt
│       │   │       │   │   ├── LocalMCPRuntimeCommandDispatchError.kt
│       │   │       │   │   ├── LocalMCPRuntimeCommandDispatchService.kt
│       │   │       │   │   ├── LocalMCPRuntimeControlErrorExtensions.kt
│       │   │       │   │   ├── LocalMCPRuntimeControlErrors.kt
│       │   │       │   │   └── LocalMCPRuntimeControlService.kt
│       │   │       │   └── toolcall/
│       │   │       │       ├── DefaultLocalMCPToolCallDispatchService.kt
│       │   │       │       ├── LocalMCPToolCallDispatchError.kt
│       │   │       │       └── LocalMCPToolCallDispatchService.kt
│       │   │       ├── protocol/
│       │   │       │   ├── codec/
│       │   │       │   │   └── WorkerServerWebSocketMessageCodec.kt
│       │   │       │   ├── handshake/
│       │   │       │   │   ├── WorkerSessionHelloError.kt
│       │   │       │   │   └── WorkerSessionHelloHandler.kt
│       │   │       │   └── routing/
│       │   │       │       └── WorkerServerIncomingMessageRouter.kt
│       │   │       └── session/
│       │   │           ├── ConnectedWorkerSession.kt
│       │   │           ├── InMemoryWorkerSessionRegistry.kt
│       │   │           ├── WorkerSessionRegistry.kt
│       │   │           └── WorkerSessionState.kt
│       │   └── resources/default-config/
│       │       ├── application.json
│       │       ├── env-mapping.json
│       │       └── setup.json
│       └── test/kotlin/eu/torvian/chatbot/server/
│           ├── config/
│           │   └── ConfigAssemblerSslSanTest.kt
│           ├── data/dao/exposed/
│           │   ├── ApiSecretDaoExposedTest.kt
│           │   ├── GroupDaoExposedTest.kt
│           │   ├── GroupOwnershipDaoExposedTest.kt
│           │   ├── LLMProviderDaoExposedTest.kt
│           │   ├── LocalMCPServerDaoExposedTest.kt
│           │   ├── LocalMCPToolDefinitionDaoExposedTest.kt
│           │   ├── MessageDaoExposedTest.kt
│           │   ├── ModelAccessDaoExposedTest.kt
│           │   ├── ModelDaoExposedTest.kt
│           │   ├── ModelOwnershipDaoExposedTest.kt
│           │   ├── PermissionDaoExposedTest.kt
│           │   ├── ProviderAccessDaoExposedTest.kt
│           │   ├── ProviderOwnershipDaoExposedTest.kt
│           │   ├── RoleDaoExposedTest.kt
│           │   ├── RolePermissionDaoExposedTest.kt
│           │   ├── SessionDaoExposedTest.kt
│           │   ├── SessionOwnershipDaoExposedTest.kt
│           │   ├── SessionToolConfigDaoExposedTest.kt
│           │   ├── SettingsAccessDaoExposedTest.kt
│           │   ├── SettingsDaoExposedTest.kt
│           │   ├── SettingsOwnershipDaoExposedTest.kt
│           │   ├── ToolCallDaoExposedTest.kt
│           │   ├── ToolDefinitionDaoExposedTest.kt
│           │   ├── UserDaoExposedTest.kt
│           │   ├── UserRoleAssignmentDaoExposedTest.kt
│           │   └── UserSessionDaoExposedTest.kt
│           ├── domain/security/
│           │   └── JwtConfigTest.kt
│           ├── ktor/
│           │   ├── auth/
│           │   │   └── WebSocketAuthHeaderExtractorTest.kt
│           │   └── routes/
│           │       ├── AuthRoutesTest.kt
│           │       ├── GroupRoutesTest.kt
│           │       ├── LocalMCPServerRoutesTest.kt
│           │       ├── MessageRoutesTest.kt
│           │       ├── ModelRoutesTest.kt
│           │       ├── ModelsAuthTest.kt
│           │       ├── ProviderRoutesTest.kt
│           │       ├── ProvidersAuthTest.kt
│           │       ├── SessionRoutesTest.kt
│           │       ├── SettingsAuthTest.kt
│           │       ├── SettingsRoutesTest.kt
│           │       ├── UserGroupRoutesTest.kt
│           │       ├── UserRoutesTest.kt
│           │       └── WorkerRoutesTest.kt
│           ├── main/
│           │   └── DatabaseMigratorTest.kt
│           ├── service/
│           │   ├── core/impl/
│           │   │   ├── ChatServiceImplTest.kt
│           │   │   ├── GroupServiceImplTest.kt
│           │   │   ├── LLMModelServiceImplTest.kt
│           │   │   ├── LLMProviderServiceImplTest.kt
│           │   │   ├── LocalMCPServerServiceImplTest.kt
│           │   │   ├── MessageServiceImplDeleteTest.kt
│           │   │   ├── MessageServiceImplSingleDeleteTest.kt
│           │   │   ├── MessageServiceImplTest.kt
│           │   │   ├── ModelSettingsServiceImplTest.kt
│           │   │   ├── SessionServiceImplCloneTest.kt
│           │   │   ├── SessionServiceImplTest.kt
│           │   │   ├── UserGroupServiceImplTest.kt
│           │   │   ├── UserServiceAdminTest.kt
│           │   │   ├── UserServiceImplTest.kt
│           │   │   └── WorkerServiceImplTest.kt
│           │   ├── llm/
│           │   │   ├── discovery/
│           │   │   │   ├── OllamaModelDiscoveryStrategyTest.kt
│           │   │   │   ├── OpenAIModelDiscoveryStrategyTest.kt
│           │   │   │   └── OpenRouterModelDiscoveryStrategyTest.kt
│           │   │   ├── strategy/
│           │   │   │   ├── OllamaChatStrategyTest.kt
│           │   │   │   └── OpenAIChatStrategyTest.kt
│           │   │   ├── LLMApiClientKtorTest.kt
│           │   │   └── LLMApiClientStub.kt
│           │   ├── mcp/
│           │   │   └── LocalMCPExecutorTest.kt
│           │   ├── security/
│           │   │   ├── authorizer/
│           │   │   │   ├── ModelResourceAuthorizerTest.kt
│           │   │   │   ├── ProviderResourceAuthorizerTest.kt
│           │   │   │   └── SettingsResourceAuthorizerTest.kt
│           │   │   ├── AuthenticationServiceImplTest.kt
│           │   │   ├── AuthorizationServiceImplTest.kt
│           │   │   ├── BCryptPasswordServiceTest.kt
│           │   │   └── DbEncryptedCredentialManagerTest.kt
│           │   ├── setup/
│           │   │   ├── InitializationCoordinatorTest.kt
│           │   │   ├── ToolDefinitionInitializerTest.kt
│           │   │   └── UserAccountInitializerTest.kt
│           │   └── tool/impl/
│           │       └── WeatherToolExecutorTest.kt
│           ├── testutils/
│           │   ├── auth/
│           │   │   └── TestAuthHelper.kt
│           │   ├── data/
│           │   │   ├── ExposedTestDataManager.kt
│           │   │   ├── Table.kt
│           │   │   ├── TestDataManager.kt
│           │   │   ├── TestDataSet.kt
│           │   │   └── TestDefaults.kt
│           │   ├── koin/
│           │   │   ├── defaultTestConfigModule.kt
│           │   │   ├── defaultTestContainer.kt
│           │   │   ├── testDatabaseModule.kt
│           │   │   └── testSetupModule.kt
│           │   ├── ktor/
│           │   │   └── myTestApplication.kt
│           │   └── service/
│           │       ├── WeatherToolExecutorStub.kt
│           │       └── WebSearchToolExecutorStub.kt
│           └── worker/
│               ├── command/
│               │   ├── pending/
│               │   │   └── PendingWorkerCommandRegistryTest.kt
│               │   └── DefaultWorkerCommandDispatchServiceTest.kt
│               ├── mcp/
│               │   ├── command/impl/
│               │   │   └── DefaultLocalMCPRuntimeCommandDispatchServiceTest.kt
│               │   ├── configsync/
│               │   │   └── DefaultLocalMCPServerConfigSyncServiceTest.kt
│               │   ├── runtimecontrol/
│               │   │   └── DefaultLocalMCPRuntimeControlServiceTest.kt
│               │   └── toolcall/
│               │       └── DefaultLocalMCPToolCallDispatchServiceTest.kt
│               └── protocol/routing/
│                   ├── WorkerServerIncomingMessageRouterTest.kt
│                   └── WorkerServerWorkerWebSocketRoutesTest.kt
```

## Worker Module
```text
├── worker/
│   ├── dev-config-sample/
│   │   ├── application.json
│   │   ├── env-mapping.json
│   │   ├── secrets.json
│   │   └── setup.json
│   └── src/
│       ├── main/
│       │   ├── dist/config/
│       │   │   ├── application_example.json
│       │   │   ├── application.json
│       │   │   ├── env-mapping.json
│       │   │   ├── secrets_example.json
│       │   │   ├── setup_example.json
│       │   │   └── setup.json
│       │   ├── kotlin/eu/torvian/chatbot/worker/
│       │   │   ├── auth/
│       │   │   │   ├── ChallengeSigner.kt
│       │   │   │   ├── ChallengeSignerError.kt
│       │   │   │   ├── DefaultWorkerAuthenticatedRequestExecutor.kt
│       │   │   │   ├── FileServiceTokenStore.kt
│       │   │   │   ├── KtorWorkerAuthApi.kt
│       │   │   │   ├── PemChallengeSigner.kt
│       │   │   │   ├── ServiceTokenStore.kt
│       │   │   │   ├── ServiceTokenStoreError.kt
│       │   │   │   ├── StoredServiceToken.kt
│       │   │   │   ├── WorkerAuthApi.kt
│       │   │   │   ├── WorkerAuthApiError.kt
│       │   │   │   ├── WorkerAuthenticatedRequestError.kt
│       │   │   │   ├── WorkerAuthenticatedRequestExecutor.kt
│       │   │   │   ├── WorkerAuthManager.kt
│       │   │   │   ├── WorkerAuthManagerError.kt
│       │   │   │   └── WorkerAuthManagerImpl.kt
│       │   │   ├── config/
│       │   │   │   ├── ConfigAssembler.kt
│       │   │   │   ├── ConfigDtos.kt
│       │   │   │   ├── Configuration.kt
│       │   │   │   ├── DefaultWorkerConfigLoader.kt
│       │   │   │   ├── ResolvedPaths.kt
│       │   │   │   ├── WorkerConfigError.kt
│       │   │   │   └── WorkerConfigLoader.kt
│       │   │   ├── koin/
│       │   │   │   └── workerModule.kt
│       │   │   ├── main/
│       │   │   │   ├── WorkerCliOptions.kt
│       │   │   │   ├── WorkerCliParser.kt
│       │   │   │   ├── WorkerMain.kt
│       │   │   │   └── WorkerMainError.kt
│       │   │   ├── mcp/
│       │   │   │   ├── api/
│       │   │   │   │   ├── AssignedConfigBootstrapper.kt
│       │   │   │   │   ├── KtorWorkerMcpServerApi.kt
│       │   │   │   │   └── WorkerMcpServerApi.kt
│       │   │   │   ├── DummyMcpRuntimeCommandExecutor.kt
│       │   │   │   ├── InMemoryMcpServerConfigStore.kt
│       │   │   │   ├── JvmMcpProcessManager.kt
│       │   │   │   ├── McpClientConnectionStatus.kt
│       │   │   │   ├── McpClientErrors.kt
│       │   │   │   ├── McpClientService.kt
│       │   │   │   ├── McpClientServiceImpl.kt
│       │   │   │   ├── McpDiscoveredTool.kt
│       │   │   │   ├── McpProcessManager.kt
│       │   │   │   ├── McpProcessManagerError.kt
│       │   │   │   ├── McpProcessState.kt
│       │   │   │   ├── McpProcessStatus.kt
│       │   │   │   ├── McpRuntimeCommandExecutor.kt
│       │   │   │   ├── McpRuntimeCommandExecutorImpl.kt
│       │   │   │   ├── McpRuntimeError.kt
│       │   │   │   ├── McpRuntimeService.kt
│       │   │   │   ├── McpRuntimeServiceImpl.kt
│       │   │   │   ├── McpServerConfigStore.kt
│       │   │   │   ├── McpTestConnectionOutcome.kt
│       │   │   │   ├── McpToolCallExecutor.kt
│       │   │   │   ├── McpToolCallExecutorImpl.kt
│       │   │   │   └── McpToolCallOutcome.kt
│       │   │   ├── protocol/
│       │   │   │   ├── factory/
│       │   │   │   │   ├── InteractionFactory.kt
│       │   │   │   │   ├── McpRuntimeCommandInteractionFactory.kt
│       │   │   │   │   ├── McpToolCallInteractionFactory.kt
│       │   │   │   │   └── ToolCallInteractionFactory.kt
│       │   │   │   ├── handshake/
│       │   │   │   │   ├── HelloInteraction.kt
│       │   │   │   │   ├── HelloStarter.kt
│       │   │   │   │   ├── HelloStartResult.kt
│       │   │   │   │   ├── InMemorySessionHandshakeContext.kt
│       │   │   │   │   ├── SessionHandshakeContext.kt
│       │   │   │   │   ├── SessionHandshakeState.kt
│       │   │   │   │   └── SessionWelcomeState.kt
│       │   │   │   ├── ids/
│       │   │   │   │   ├── InteractionIdProvider.kt
│       │   │   │   │   ├── MessageIdProvider.kt
│       │   │   │   │   ├── UuidInteractionIdProvider.kt
│       │   │   │   │   └── UuidMessageIdProvider.kt
│       │   │   │   ├── interaction/
│       │   │   │   │   ├── ChannelBackedInteraction.kt
│       │   │   │   │   ├── Interaction.kt
│       │   │   │   │   ├── McpRuntimeCommandInteraction.kt
│       │   │   │   │   ├── McpToolCallInteraction.kt
│       │   │   │   │   └── ToolCallInteraction.kt
│       │   │   │   ├── registry/
│       │   │   │   │   ├── InMemoryInteractionRegistry.kt
│       │   │   │   │   └── InteractionRegistry.kt
│       │   │   │   ├── routing/
│       │   │   │   │   ├── CommandRequestProcessor.kt
│       │   │   │   │   ├── IncomingMessageProcessor.kt
│       │   │   │   │   └── WorkerProtocolMessageRouter.kt
│       │   │   │   └── transport/
│       │   │   │       ├── OutboundMessageEmitter.kt
│       │   │   │       ├── OutboundMessageEmitterHolder.kt
│       │   │   │       ├── TransportConnectionLoopRunner.kt
│       │   │   │       ├── WebSocketConnectionLoop.kt
│       │   │   │       ├── WebSocketMessageCodec.kt
│       │   │   │       ├── WebSocketMessageCodecError.kt
│       │   │   │       ├── WebSocketSessionResult.kt
│       │   │   │       ├── WebSocketSessionRunner.kt
│       │   │   │       └── WebSocketTransportConfig.kt
│       │   │   ├── runtime/
│       │   │   │   ├── WorkerRuntime.kt
│       │   │   │   ├── WorkerRuntimeError.kt
│       │   │   │   └── WorkerRuntimeImpl.kt
│       │   │   └── setup/
│       │   │       ├── DefaultPrivateKeyProvider.kt
│       │   │       ├── DefaultWorkerSetupCredentialProvider.kt
│       │   │       ├── DefaultWorkerSetupDisplayNameProvider.kt
│       │   │       ├── DefaultWorkerSetupManager.kt
│       │   │       ├── FileSecretsStore.kt
│       │   │       ├── KtorWorkerSetupApi.kt
│       │   │       ├── PrivateKeyLoadError.kt
│       │   │       ├── PrivateKeyProvider.kt
│       │   │       ├── Secrets.kt
│       │   │       ├── SecretsStore.kt
│       │   │       ├── SecretsStoreError.kt
│       │   │       ├── WorkerCertificateService.kt
│       │   │       ├── WorkerSetupApi.kt
│       │   │       ├── WorkerSetupCredentialProvider.kt
│       │   │       ├── WorkerSetupCredentials.kt
│       │   │       ├── WorkerSetupDisplayNameProvider.kt
│       │   │       ├── WorkerSetupError.kt
│       │   │       └── WorkerSetupManager.kt
│       │   └── resources/default-config/
│       │       ├── application.json
│       │       ├── env-mapping.json
│       │       └── setup.json
│       └── test/kotlin/eu/torvian/chatbot/worker/
│           ├── auth/
│           │   ├── DefaultWorkerAuthenticatedRequestExecutorTest.kt
│           │   └── WorkerAuthManagerTest.kt
│           ├── config/
│           │   └── WorkerConfigLoaderTest.kt
│           ├── main/
│           │   ├── WorkerCliParserTest.kt
│           │   └── WorkerMainTest.kt
│           ├── mcp/
│           │   ├── api/
│           │   │   └── KtorWorkerMcpServerApiTest.kt
│           │   ├── McpRuntimeCommandExecutorImplTest.kt
│           │   ├── McpRuntimeServiceImplTest.kt
│           │   └── McpToolCallExecutorImplTest.kt
│           ├── protocol/
│           │   ├── handshake/
│           │   │   ├── HelloInteractionTest.kt
│           │   │   ├── HelloStarterTest.kt
│           │   │   └── SessionHandshakeContextTest.kt
│           │   ├── interaction/
│           │   │   ├── McpRuntimeCommandInteractionTest.kt
│           │   │   ├── McpToolCallInteractionTest.kt
│           │   │   └── ToolCallInteractionTest.kt
│           │   ├── routing/
│           │   │   ├── CommandRequestProcessorTest.kt
│           │   │   └── WorkerProtocolMessageRouterTest.kt
│           │   └── transport/
│           │       ├── WebSocketConnectionLoopTest.kt
│           │       └── WebSocketTransportConfigTest.kt
│           ├── runtime/
│           │   └── WorkerRuntimeImplTest.kt
│           └── setup/
│               ├── DefaultPrivateKeyProviderTest.kt
│               └── DefaultWorkerSetupManagerTest.kt
````


## App Module
```text
├── app/
│   ├── dev-config-sample/
│   │   ├── config.json
│   │   ├── secrets.json
│   │   └── setup.json
│   └── src/
│       ├── androidMain/kotlin/eu/torvian/chatbot/app/
│       │   ├── compose/common/
│       │   │   └── ScrollbarWrapper.android.kt
│       │   ├── config/
│       │   │   └── KeyGenerator.android.kt
│       │   ├── database/dao/
│       │   │   └── ExceptionCheckerAndroid.kt
│       │   ├── koin/
│       │   │   └── androidModule.kt
│       │   ├── main/
│       │   │   └── MainActivity.kt
│       │   ├── service/
│       │   │   ├── auth/
│       │   │   │   └── FilePermissions.android.kt
│       │   │   └── clipboard/
│       │   │       └── ClipboardServiceAndroid.kt
│       │   └── utils/
│       │       ├── misc/
│       │       │   ├── ioDispatcher.android.kt
│       │       │   └── KmpLogger.android.kt
│       │       ├── platform/
│       │       │   └── FilePicker.kt
│       │       └── transaction/
│       │           └── databaseDispatcher.android.kt
│       ├── commonMain/
│       │   ├── composeResources/files/config/
│       │   │   ├── default_config.json
│       │   │   └── default_setup.json
│       │   └── kotlin/eu/torvian/chatbot/app/
│       │       ├── compose/
│       │       │   ├── admin/
│       │       │   │   ├── usergroups/
│       │       │   │   │   ├── CreateGroupDialog.kt
│       │       │   │   │   ├── DeleteGroupDialog.kt
│       │       │   │   │   ├── EditGroupDialog.kt
│       │       │   │   │   ├── GroupManagementActions.kt
│       │       │   │   │   ├── GroupManagementDialogs.kt
│       │       │   │   │   ├── ManageGroupMembersDialog.kt
│       │       │   │   │   ├── UserGroupDetailPanel.kt
│       │       │   │   │   ├── UserGroupListItem.kt
│       │       │   │   │   ├── UserGroupListPanel.kt
│       │       │   │   │   ├── UserGroupManagementTab.kt
│       │       │   │   │   └── UserGroupManagementTabRoute.kt
│       │       │   │   ├── users/
│       │       │   │   │   ├── ChangePasswordChangeRequiredDialog.kt
│       │       │   │   │   ├── ChangePasswordDialog.kt
│       │       │   │   │   ├── ChangeUserStatusDialog.kt
│       │       │   │   │   ├── DeleteUserDialog.kt
│       │       │   │   │   ├── EditUserDialog.kt
│       │       │   │   │   ├── ManageRolesDialog.kt
│       │       │   │   │   ├── UserDetailPanel.kt
│       │       │   │   │   ├── UserListItem.kt
│       │       │   │   │   ├── UserListPanel.kt
│       │       │   │   │   ├── UserManagementActions.kt
│       │       │   │   │   ├── UserManagementDialogs.kt
│       │       │   │   │   ├── UserManagementTab.kt
│       │       │   │   │   └── UserManagementTabRoute.kt
│       │       │   │   └── AdminScreen.kt
│       │       │   ├── auth/
│       │       │   │   ├── AddAccountDialog.kt
│       │       │   │   ├── AuthComponents.kt
│       │       │   │   ├── AuthDialogs.kt
│       │       │   │   ├── AuthErrorScreen.kt
│       │       │   │   ├── AuthLoadingScreen.kt
│       │       │   │   ├── AvailableAccountsSection.kt
│       │       │   │   ├── ForcePasswordChangeScreen.kt
│       │       │   │   ├── LoginScreen.kt
│       │       │   │   ├── RegisterScreen.kt
│       │       │   │   ├── RemoveAccountConfirmationDialog.kt
│       │       │   │   └── SwitchAccountDialog.kt
│       │       │   ├── chatarea/
│       │       │   │   ├── ChatArea.kt
│       │       │   │   ├── ChatAreaActions.kt
│       │       │   │   ├── ChatAreaState.kt
│       │       │   │   ├── ChatTopBarContent.kt
│       │       │   │   ├── Dialogs.kt
│       │       │   │   ├── FileReferenceBadge.kt
│       │       │   │   ├── FileReferenceDetailsDialog.kt
│       │       │   │   ├── FileReferencesManagementDialog.kt
│       │       │   │   ├── InputArea.kt
│       │       │   │   ├── InputAreaActions.kt
│       │       │   │   ├── MessageActionRow.kt
│       │       │   │   ├── MessageActions.kt
│       │       │   │   ├── MessageContent.kt
│       │       │   │   ├── MessageItem.kt
│       │       │   │   ├── MessageList.kt
│       │       │   │   ├── ToolCallBadge.kt
│       │       │   │   ├── ToolCallDetailsDialog.kt
│       │       │   │   └── ToolConfigPanel.kt
│       │       │   ├── common/
│       │       │   │   ├── ConfigFormComponents.kt
│       │       │   │   ├── DataStateComponents.kt
│       │       │   │   ├── ErrorStateDisplay.kt
│       │       │   │   ├── LoadingOverlay.kt
│       │       │   │   ├── OverflowTooltipText.kt
│       │       │   │   ├── PlainTooltipBox.kt
│       │       │   │   └── ScrollbarWrapper.kt
│       │       │   ├── dialogs/
│       │       │   │   └── CertificateWarningDialog.kt
│       │       │   ├── permissions/
│       │       │   │   └── PermissionGate.kt
│       │       │   ├── preview/
│       │       │   │   ├── ChatAreaPreview.kt
│       │       │   │   ├── LoadingOverlayPreview.kt
│       │       │   │   └── SessionListPanelPreview.kt
│       │       │   ├── sessionlist/
│       │       │   │   ├── DialogActions.kt
│       │       │   │   ├── Dialogs.kt
│       │       │   │   ├── GroupComponents.kt
│       │       │   │   ├── GroupEditingActions.kt
│       │       │   │   ├── HeaderAndInput.kt
│       │       │   │   ├── MainContent.kt
│       │       │   │   ├── SessionListActions.kt
│       │       │   │   ├── SessionListItem.kt
│       │       │   │   ├── SessionListPanel.kt
│       │       │   │   └── SessionListState.kt
│       │       │   ├── settings/
│       │       │   │   ├── dialogs/
│       │       │   │   │   └── ManageAccessDialog.kt
│       │       │   │   ├── DetailRow.kt
│       │       │   │   ├── LocalMCPServerDetailPanel.kt
│       │       │   │   ├── LocalMCPServerDetailsPage.kt
│       │       │   │   ├── LocalMCPServerDialogs.kt
│       │       │   │   ├── LocalMCPServersListPage.kt
│       │       │   │   ├── LocalMCPServersListPanel.kt
│       │       │   │   ├── LocalMCPServersTab.kt
│       │       │   │   ├── LocalMCPServersTabRoute.kt
│       │       │   │   ├── LocalMCPServerState.kt
│       │       │   │   ├── ModelDetailsContent.kt
│       │       │   │   ├── ModelDetailsPage.kt
│       │       │   │   ├── ModelFormDialog.kt
│       │       │   │   ├── ModelsDialogs.kt
│       │       │   │   ├── ModelSettingsConfigTab.kt
│       │       │   │   ├── ModelSettingsConfigTabRoute.kt
│       │       │   │   ├── ModelSettingsDetailPanel.kt
│       │       │   │   ├── ModelSettingsDetailsPage.kt
│       │       │   │   ├── ModelSettingsDialogs.kt
│       │       │   │   ├── ModelSettingsFormDialog.kt
│       │       │   │   ├── ModelSettingsListPage.kt
│       │       │   │   ├── ModelSettingsListPanel.kt
│       │       │   │   ├── ModelsListPage.kt
│       │       │   │   ├── ModelsListPanel.kt
│       │       │   │   ├── ModelsTab.kt
│       │       │   │   ├── ModelsTabRoute.kt
│       │       │   │   ├── ProviderDetailsContent.kt
│       │       │   │   ├── ProviderDetailsPage.kt
│       │       │   │   ├── ProviderDetailsSection.kt
│       │       │   │   ├── ProviderDialogs.kt
│       │       │   │   ├── ProviderListItem.kt
│       │       │   │   ├── ProvidersListPage.kt
│       │       │   │   ├── ProvidersListPanel.kt
│       │       │   │   ├── ProvidersTab.kt
│       │       │   │   ├── ProvidersTabRoute.kt
│       │       │   │   ├── SettingsActions.kt
│       │       │   │   ├── SettingsBreadcrumbs.kt
│       │       │   │   ├── SettingsCategory.kt
│       │       │   │   ├── SettingsDetailPage.kt
│       │       │   │   ├── SettingsListPageTemplate.kt
│       │       │   │   ├── SettingsScreen.kt
│       │       │   │   ├── SettingsSidebar.kt
│       │       │   │   ├── SettingsState.kt
│       │       │   │   ├── SettingsTopBarContent.kt
│       │       │   │   ├── WorkersTab.kt
│       │       │   │   └── WorkersTabRoute.kt
│       │       │   ├── setup/
│       │       │   │   └── SetupScreen.kt
│       │       │   ├── snackbar/
│       │       │   │   ├── SharedSnackbar.kt
│       │       │   │   └── SnackbarVisualsWithError.kt
│       │       │   ├── startup/
│       │       │   │   ├── StartupErrorScreen.kt
│       │       │   │   └── StartupLoadingScreen.kt
│       │       │   ├── topbar/
│       │       │   │   ├── TopBarContent.kt
│       │       │   │   └── TopBarContentProvider.kt
│       │       │   ├── AppShell.kt
│       │       │   ├── AuthenticationFlow.kt
│       │       │   ├── ChatScreen.kt
│       │       │   ├── ChatScreenContent.kt
│       │       │   ├── MainApplicationFlow.kt
│       │       │   └── UserMenu.kt
│       │       ├── config/
│       │       │   ├── AppConfiguration.kt
│       │       │   ├── ClientConfigLoader.kt
│       │       │   ├── ConfigAssembler.kt
│       │       │   ├── ConfigDtos.kt
│       │       │   ├── ConfigError.kt
│       │       │   ├── KeyGenerator.kt
│       │       │   ├── NetworkConfig.kt
│       │       │   └── StorageConfig.kt
│       │       ├── domain/
│       │       │   ├── contracts/
│       │       │   │   ├── DataState.kt
│       │       │   │   ├── DataStateExtensions.kt
│       │       │   │   ├── FormMode.kt
│       │       │   │   ├── GrantAccessFormState.kt
│       │       │   │   ├── ModelConfigData.kt
│       │       │   │   ├── ModelFormState.kt
│       │       │   │   ├── ModelsDialogState.kt
│       │       │   │   ├── ModelSettingsDialogState.kt
│       │       │   │   ├── ModelSettingsFormState.kt
│       │       │   │   ├── ProviderFormState.kt
│       │       │   │   ├── ProvidersDialogState.kt
│       │       │   │   ├── SessionListData.kt
│       │       │   │   ├── SessionListDialogState.kt
│       │       │   │   └── WorkersDialogState.kt
│       │       │   ├── events/
│       │       │   │   ├── AccountSwitchedEvent.kt
│       │       │   │   ├── ApiRequestError.kt
│       │       │   │   ├── AppError.kt
│       │       │   │   ├── AppEvent.kt
│       │       │   │   ├── AppSuccess.kt
│       │       │   │   ├── AppWarning.kt
│       │       │   │   ├── GenericAppError.kt
│       │       │   │   ├── GenericAppSuccess.kt
│       │       │   │   ├── GenericAppWarning.kt
│       │       │   │   ├── InternalEvent.kt
│       │       │   │   ├── RepositoryAppError.kt
│       │       │   │   └── SnackbarInteractionEvent.kt
│       │       │   ├── models/
│       │       │   │   └── LocalMCPServerMappers.kt
│       │       │   └── navigation/
│       │       │       └── AppRoute.kt
│       │       ├── koin/
│       │       │   └── appModule.kt
│       │       ├── repository/
│       │       │   ├── impl/
│       │       │   │   ├── DefaultAuthRepository.kt
│       │       │   │   ├── DefaultGroupRepository.kt
│       │       │   │   ├── DefaultLocalMCPServerRepository.kt
│       │       │   │   ├── DefaultLocalMCPServerRuntimeStatusRepository.kt
│       │       │   │   ├── DefaultLocalMCPToolRepository.kt
│       │       │   │   ├── DefaultModelRepository.kt
│       │       │   │   ├── DefaultModelSettingsRepository.kt
│       │       │   │   ├── DefaultProviderRepository.kt
│       │       │   │   ├── DefaultRoleRepository.kt
│       │       │   │   ├── DefaultSessionRepository.kt
│       │       │   │   ├── DefaultToolRepository.kt
│       │       │   │   ├── DefaultUserGroupRepository.kt
│       │       │   │   ├── DefaultUserRepository.kt
│       │       │   │   └── DefaultWorkerRepository.kt
│       │       │   ├── AuthRepository.kt
│       │       │   ├── AuthState.kt
│       │       │   ├── GroupRepository.kt
│       │       │   ├── LocalMCPServerRepository.kt
│       │       │   ├── LocalMCPServerRuntimeStatusRepository.kt
│       │       │   ├── LocalMCPToolRepository.kt
│       │       │   ├── ModelRepository.kt
│       │       │   ├── ModelSettingsRepository.kt
│       │       │   ├── ProviderRepository.kt
│       │       │   ├── RepositoryError.kt
│       │       │   ├── RoleRepository.kt
│       │       │   ├── SessionRepository.kt
│       │       │   ├── ToolRepository.kt
│       │       │   ├── UserGroupRepository.kt
│       │       │   ├── UserRepository.kt
│       │       │   └── WorkerRepository.kt
│       │       ├── service/
│       │       │   ├── api/
│       │       │   │   ├── ktor/
│       │       │   │   │   ├── BaseApiResourceClient.kt
│       │       │   │   │   ├── configureHttpClient.kt
│       │       │   │   │   ├── createPlatformHttpClient.kt
│       │       │   │   │   ├── HttpClientWebSocketExtension.kt
│       │       │   │   │   ├── KtorAuthApiClient.kt
│       │       │   │   │   ├── KtorChatApiClient.kt
│       │       │   │   │   ├── KtorGroupApiClient.kt
│       │       │   │   │   ├── KtorLocalMCPServerApiClient.kt
│       │       │   │   │   ├── KtorLocalMCPToolApiClient.kt
│       │       │   │   │   ├── KtorModelApiClient.kt
│       │       │   │   │   ├── KtorProviderApiClient.kt
│       │       │   │   │   ├── KtorRoleApiClient.kt
│       │       │   │   │   ├── KtorSessionApiClient.kt
│       │       │   │   │   ├── KtorSettingsApiClient.kt
│       │       │   │   │   ├── KtorToolApiClient.kt
│       │       │   │   │   ├── KtorUserApiClient.kt
│       │       │   │   │   ├── KtorUserGroupApiClient.kt
│       │       │   │   │   ├── KtorWorkerApiClient.kt
│       │       │   │   │   └── WebSocketAuthSubprotocolProvider.kt
│       │       │   │   ├── ApiResourceError.kt
│       │       │   │   ├── AuthApi.kt
│       │       │   │   ├── ChatApi.kt
│       │       │   │   ├── GroupApi.kt
│       │       │   │   ├── LocalMCPServerApi.kt
│       │       │   │   ├── LocalMCPToolApi.kt
│       │       │   │   ├── ModelApi.kt
│       │       │   │   ├── ProviderApi.kt
│       │       │   │   ├── RoleApi.kt
│       │       │   │   ├── SessionApi.kt
│       │       │   │   ├── SettingsApi.kt
│       │       │   │   ├── ToolApi.kt
│       │       │   │   ├── UserApi.kt
│       │       │   │   ├── UserGroupApi.kt
│       │       │   │   └── WorkerApi.kt
│       │       │   ├── auth/
│       │       │   │   ├── AccountData.kt
│       │       │   │   ├── AuthenticationFailureEvent.kt
│       │       │   │   ├── createAuthenticatedHttpClient.kt
│       │       │   │   ├── FilePermissions.kt
│       │       │   │   ├── FileSystemTokenStorage.kt
│       │       │   │   ├── TokenStorage.kt
│       │       │   │   ├── TokenStorageData.kt
│       │       │   │   └── TokenStorageError.kt
│       │       │   ├── clipboard/
│       │       │   │   └── ClipboardService.kt
│       │       │   ├── mcp/
│       │       │   │   ├── LocalMCPServerManager.kt
│       │       │   │   ├── LocalMCPServerManagerError.kt
│       │       │   │   ├── LocalMCPServerManagerImpl.kt
│       │       │   │   └── LocalMCPServerOverview.kt
│       │       │   ├── misc/
│       │       │   │   └── EventBus.kt
│       │       │   └── security/
│       │       │       ├── CertificateDetails.kt
│       │       │       ├── CertificateStorage.kt
│       │       │       ├── CertificateStorageError.kt
│       │       │       ├── CertificateTrustService.kt
│       │       │       └── FileSystemCertificateStorage.kt
│       │       ├── utils/
│       │       │   ├── misc/
│       │       │   │   ├── ioDispatcher.kt
│       │       │   │   ├── KmpLogger.kt
│       │       │   │   └── LruCache.kt
│       │       │   ├── permissions/
│       │       │   │   └── PermissionChecker.kt
│       │       │   └── platform/
│       │       │       ├── FilePathUtils.kt
│       │       │       └── FilePicker.kt
│       │       └── viewmodel/
│       │           ├── admin/
│       │           │   ├── UserGroupManagementState.kt
│       │           │   ├── UserGroupManagementViewModel.kt
│       │           │   ├── UserManagementState.kt
│       │           │   └── UserManagementViewModel.kt
│       │           ├── auth/
│       │           │   ├── AuthDialogState.kt
│       │           │   ├── AuthFormState.kt
│       │           │   ├── AuthFormValidation.kt
│       │           │   └── AuthViewModel.kt
│       │           ├── chat/
│       │           │   ├── state/
│       │           │   │   ├── ChatAreaDialogState.kt
│       │           │   │   ├── ChatState.kt
│       │           │   │   └── ChatStateImpl.kt
│       │           │   ├── usecase/
│       │           │   │   ├── CopyToClipboardUseCase.kt
│       │           │   │   ├── DeleteMessageUseCase.kt
│       │           │   │   ├── EditMessageUseCase.kt
│       │           │   │   ├── FileReferenceUseCase.kt
│       │           │   │   ├── InsertMessageUseCase.kt
│       │           │   │   ├── LoadSessionUseCase.kt
│       │           │   │   ├── ReplyUseCase.kt
│       │           │   │   ├── SelectModelUseCase.kt
│       │           │   │   ├── SelectSettingsUseCase.kt
│       │           │   │   ├── SendMessageUseCase.kt
│       │           │   │   ├── SwitchBranchUseCase.kt
│       │           │   │   ├── ToggleToolsUseCase.kt
│       │           │   │   └── UpdateInputUseCase.kt
│       │           │   └── ChatViewModel.kt
│       │           ├── common/
│       │           │   ├── CoroutineScopeProvider.kt
│       │           │   └── NotificationService.kt
│       │           ├── setup/
│       │           │   ├── CompleteSetupUseCase.kt
│       │           │   ├── SetupEvent.kt
│       │           │   ├── SetupState.kt
│       │           │   └── SetupViewModel.kt
│       │           ├── startup/
│       │           │   ├── LoadStartupConfigurationUseCase.kt
│       │           │   ├── StartupState.kt
│       │           │   └── StartupViewModel.kt
│       │           ├── LocalMCPServerViewModel.kt
│       │           ├── ModelConfigViewModel.kt
│       │           ├── ModelSettingsViewModel.kt
│       │           ├── ProviderConfigViewModel.kt
│       │           ├── SessionListViewModel.kt
│       │           └── WorkersViewModel.kt
│       ├── commonTest/kotlin/eu/torvian/chatbot/app/
│       │   ├── testutils/
│       │   │   ├── data/
│       │   │   │   └── TestData.kt
│       │   │   ├── misc/
│       │   │   │   └── TestClock.kt
│       │   │   └── viewmodel/
│       │   │       └── FlowTestUtils.kt.kt
│       │   └── utils/misc/
│       │       └── LruCacheTest.kt
│       ├── desktopAndroidMain/kotlin/eu/torvian/chatbot/app/
│       │   ├── config/
│       │   │   └── FileSystemClientConfigLoader.kt
│       │   └── service/
│       │       ├── api/ktor/
│       │       │   └── createPlatformHttpClient.kt
│       │       └── security/
│       │           └── CustomTrustManager.kt
│       ├── desktopMain/kotlin/eu/torvian/chatbot/app/
│       │   ├── compose/common/
│       │   │   └── ScrollbarWrapper.desktop.kt
│       │   ├── config/
│       │   │   └── KeyGenerator.desktop.kt
│       │   ├── koin/
│       │   │   └── desktopModule.kt
│       │   ├── main/
│       │   │   └── AppMain.kt
│       │   ├── service/
│       │   │   ├── auth/
│       │   │   │   └── FilePermissions.desktop.kt
│       │   │   └── clipboard/
│       │   │       └── ClipboardServiceDesktop.kt
│       │   └── utils/
│       │       ├── misc/
│       │       │   ├── createKmpLogger.desktop.kt
│       │       │   └── ioDispatcher.desktop.kt
│       │       └── platform/
│       │           ├── FilePicker.kt
│       │           └── TextFileReader.kt
│       ├── desktopTest/kotlin/eu/torvian/chatbot/app/
│       │   ├── compose/
│       │   │   ├── common/
│       │   │   │   └── LoadingOverlayTest.kt
│       │   │   └── ChatAreaTest.kt
│       │   ├── repository/impl/
│       │   │   ├── DefaultAuthRepositoryAccountManagementTest.kt
│       │   │   ├── DefaultLocalMCPServerRepositoryTest.kt
│       │   │   └── DefaultLocalMCPServerRuntimeStatusRepositoryTest.kt
│       │   ├── service/
│       │   │   ├── api/ktor/
│       │   │   │   ├── KtorChatApiClientTest.kt
│       │   │   │   ├── KtorGroupApiClientTest.kt
│       │   │   │   ├── KtorLocalMCPServerApiClientTest.kt
│       │   │   │   ├── KtorLocalMCPToolApiClientTest.kt
│       │   │   │   ├── KtorModelApiClientTest.kt
│       │   │   │   ├── KtorProviderApiClientTest.kt
│       │   │   │   ├── KtorSessionApiClientTest.kt
│       │   │   │   └── KtorSettingsApiClientTest.kt
│       │   │   ├── auth/
│       │   │   │   ├── CreateAuthenticatedHttpClientTest.kt
│       │   │   │   └── FileSystemTokenStorageTest.kt
│       │   │   └── mcp/
│       │   │       ├── LocalMCPServerManagerImplOperationsTest.kt
│       │   │       └── LocalMCPServerManagerImplOverviewTest.kt
│       │   ├── testutils/viewmodel/
│       │   │   └── TestMockkExtensions.kt
│       │   └── viewmodel/auth/
│       │       └── AuthViewModelTest.kt
│       └── wasmJsMain/kotlin/eu/torvian/chatbot/app/
│           ├── compose/common/
│           │   └── ScrollbarWrapper.wasmJs.kt
│           ├── config/
│           │   ├── KeyGenerator.wasmJs.kt
│           │   └── WebStorageClientConfigLoader.kt
│           ├── koin/
│           │   └── wasmJsModule.kt
│           ├── main/
│           │   └── AppMain.kt
│           ├── service/
│           │   ├── api/ktor/
│           │   │   ├── BrowserWebSocketAuthSubprotocolProvider.kt
│           │   │   └── createPlatformHttpClient.kt
│           │   ├── auth/
│           │   │   ├── BrowserTokenStorage.kt
│           │   │   └── FilePermissions.wasmJs.kt
│           │   ├── clipboard/
│           │   │   └── ClipboardServiceWasmJs.kt
│           │   └── security/
│           │       └── BrowserCertificateStorage.kt
│           └── utils/
│               ├── misc/
│               │   ├── createKmpLogger.wasmJs.kt
│               │   └── ioDispatcher.wasmJs.kt
│               └── platform/
│                   └── FilePicker.kt
```


## Common Module
```text
├── common/
│   └── src/
│       ├── commonMain/kotlin/eu/torvian/chatbot/common/
│       │   ├── api/
│       │   │   ├── resources/
│       │   │   │   ├── Api.kt
│       │   │   │   ├── AuthResource.kt
│       │   │   │   ├── GroupResource.kt
│       │   │   │   ├── href.kt
│       │   │   │   ├── LocalMCPServerResource.kt
│       │   │   │   ├── LocalMCPToolResource.kt
│       │   │   │   ├── MessageResource.kt
│       │   │   │   ├── ModelResource.kt
│       │   │   │   ├── ProviderResource.kt
│       │   │   │   ├── RoleResource.kt
│       │   │   │   ├── SessionResource.kt
│       │   │   │   ├── SettingsResource.kt
│       │   │   │   ├── ToolResource.kt
│       │   │   │   ├── UserGroupResource.kt
│       │   │   │   ├── UserResource.kt
│       │   │   │   ├── WorkerResource.kt
│       │   │   │   └── WsResource.kt
│       │   │   ├── AccessMode.kt
│       │   │   ├── ApiError.kt
│       │   │   ├── ApiErrorCode.kt
│       │   │   ├── ChatbotApiErrorCodes.kt
│       │   │   ├── CommonApiErrorCodes.kt
│       │   │   ├── CommonPermissions.kt
│       │   │   ├── CommonRoles.kt
│       │   │   ├── CommonUserGroups.kt
│       │   │   └── CommonWebSocketProtocols.kt
│       │   ├── misc/
│       │   │   ├── di/
│       │   │   │   ├── DIContainer.kt
│       │   │   │   └── KoinDIContainer.kt
│       │   │   └── transaction/
│       │   │       ├── CoroutineContextExtensions.kt
│       │   │       ├── TransactionMarker.kt
│       │   │       └── TransactionScope.kt
│       │   ├── models/
│       │   │   ├── api/
│       │   │   │   ├── access/
│       │   │   │   │   ├── GrantAccessRequest.kt
│       │   │   │   │   ├── LLMModelDetails.kt
│       │   │   │   │   ├── LLMProviderDetails.kt
│       │   │   │   │   ├── ModelSettingsDetails.kt
│       │   │   │   │   ├── OwnerInfo.kt
│       │   │   │   │   ├── ResourceAccessDetails.kt
│       │   │   │   │   └── RevokeAccessRequest.kt
│       │   │   │   ├── admin/
│       │   │   │   │   ├── AddUserToGroupRequest.kt
│       │   │   │   │   ├── AssignRoleRequest.kt
│       │   │   │   │   ├── ChangePasswordRequest.kt
│       │   │   │   │   ├── CreateRoleRequest.kt
│       │   │   │   │   ├── CreateUserGroupRequest.kt
│       │   │   │   │   ├── UpdatePasswordChangeRequiredRequest.kt
│       │   │   │   │   ├── UpdateRoleRequest.kt
│       │   │   │   │   ├── UpdateUserGroupRequest.kt
│       │   │   │   │   ├── UpdateUserRequest.kt
│       │   │   │   │   └── UpdateUserStatusRequest.kt
│       │   │   │   ├── auth/
│       │   │   │   │   ├── LoginRequest.kt
│       │   │   │   │   ├── LoginResponse.kt
│       │   │   │   │   ├── RefreshTokenRequest.kt
│       │   │   │   │   ├── RegisterRequest.kt
│       │   │   │   │   ├── ServiceTokenChallengeRequest.kt
│       │   │   │   │   ├── ServiceTokenChallengeResponse.kt
│       │   │   │   │   ├── ServiceTokenRequest.kt
│       │   │   │   │   └── ServiceTokenResponse.kt
│       │   │   │   ├── core/
│       │   │   │   │   ├── AssignSessionToGroupRequest.kt
│       │   │   │   │   ├── ChatClientEvent.kt
│       │   │   │   │   ├── ChatEvent.kt
│       │   │   │   │   ├── ChatStreamEvent.kt
│       │   │   │   │   ├── CloneSessionRequest.kt
│       │   │   │   │   ├── CreateGroupRequest.kt
│       │   │   │   │   ├── CreateSessionRequest.kt
│       │   │   │   │   ├── InsertMessageRequest.kt
│       │   │   │   │   ├── ProcessNewMessageRequest.kt
│       │   │   │   │   ├── RenameGroupRequest.kt
│       │   │   │   │   ├── UpdateMessageRequest.kt
│       │   │   │   │   ├── UpdateSessionGroupRequest.kt
│       │   │   │   │   ├── UpdateSessionLeafMessageRequest.kt
│       │   │   │   │   ├── UpdateSessionModelRequest.kt
│       │   │   │   │   ├── UpdateSessionModelResponse.kt
│       │   │   │   │   ├── UpdateSessionNameRequest.kt
│       │   │   │   │   └── UpdateSessionSettingsRequest.kt
│       │   │   │   ├── llm/
│       │   │   │   │   ├── AddModelRequest.kt
│       │   │   │   │   ├── AddProviderRequest.kt
│       │   │   │   │   ├── ApiKeyStatusResponse.kt
│       │   │   │   │   ├── DiscoveredProviderModel.kt
│       │   │   │   │   ├── TestProviderConnectionRequest.kt
│       │   │   │   │   └── UpdateProviderCredentialRequest.kt
│       │   │   │   ├── mcp/
│       │   │   │   │   ├── CreateLocalMCPServerRequest.kt
│       │   │   │   │   ├── LocalMCPEnvironmentVariableDto.kt
│       │   │   │   │   ├── LocalMCPServerDto.kt
│       │   │   │   │   ├── LocalMcpServerRuntimeStatusDto.kt
│       │   │   │   │   ├── LocalMCPToolCallRequest.kt
│       │   │   │   │   ├── LocalMCPToolCallResult.kt
│       │   │   │   │   ├── LocalMCPToolRequests.kt
│       │   │   │   │   ├── TestLocalMCPServerConnectionResponse.kt
│       │   │   │   │   ├── TestLocalMCPServerDraftConnectionRequest.kt
│       │   │   │   │   └── UpdateLocalMCPServerRequest.kt
│       │   │   │   ├── tool/
│       │   │   │   │   ├── CreateToolRequest.kt
│       │   │   │   │   ├── SetToolApprovalPreferenceRequest.kt
│       │   │   │   │   ├── SetToolEnabledRequest.kt
│       │   │   │   │   ├── SetToolsEnabledRequest.kt
│       │   │   │   │   └── ToolCallApprovalResponse.kt
│       │   │   │   └── worker/
│       │   │   │       ├── protocol/
│       │   │   │       │   ├── codec/
│       │   │   │       │   │   ├── WorkerProtocolCodecError.kt
│       │   │   │       │   │   ├── WorkerProtocolJson.kt
│       │   │   │       │   │   └── WorkerProtocolPayloadCodec.kt
│       │   │   │       │   ├── constants/
│       │   │   │       │   │   ├── WorkerCommandResultStatuses.kt
│       │   │   │       │   │   ├── WorkerProtocolCommandMessageKinds.kt
│       │   │   │       │   │   ├── WorkerProtocolCommandTypes.kt
│       │   │   │       │   │   ├── WorkerProtocolMessageTypes.kt
│       │   │   │       │   │   └── WorkerProtocolRejectionReasons.kt
│       │   │   │       │   ├── core/
│       │   │   │       │   │   ├── WorkerProtocolMessage.kt
│       │   │   │       │   │   └── WorkerProtocolVersion.kt
│       │   │   │       │   ├── mapping/
│       │   │   │       │   │   ├── WorkerMcpRuntimeCommandMappingSupport.kt
│       │   │   │       │   │   ├── WorkerMcpRuntimeCommandProtocolMappingError.kt
│       │   │   │       │   │   ├── WorkerMcpRuntimeLifecycleCommandMappings.kt
│       │   │   │       │   │   ├── WorkerMcpServerConfigSyncCommandMappings.kt
│       │   │   │       │   │   ├── WorkerMcpToolCallProtocolMappingError.kt
│       │   │   │       │   │   └── WorkerMcpToolCallProtocolMappings.kt
│       │   │   │       │   └── payload/
│       │   │   │       │       ├── WorkerCommandAcceptedPayload.kt
│       │   │   │       │       ├── WorkerCommandMessagePayload.kt
│       │   │   │       │       ├── WorkerCommandRejectedPayload.kt
│       │   │   │       │       ├── WorkerCommandRequestPayload.kt
│       │   │   │       │       ├── WorkerCommandResultPayload.kt
│       │   │   │       │       ├── WorkerMcpRuntimeCommandData.kt
│       │   │   │       │       ├── WorkerSessionHelloPayload.kt
│       │   │   │       │       └── WorkerSessionWelcomePayload.kt
│       │   │   │       ├── RegisterWorkerRequest.kt
│       │   │   │       ├── RegisterWorkerResponse.kt
│       │   │   │       ├── UpdateWorkerRequest.kt
│       │   │   │       └── WorkerChallengeDto.kt
│       │   │   ├── core/
│       │   │   │   ├── ChatGroup.kt
│       │   │   │   ├── ChatMessage.kt
│       │   │   │   ├── ChatSession.kt
│       │   │   │   ├── ChatSessionSummary.kt
│       │   │   │   ├── FileReference.kt
│       │   │   │   └── MessageInsertPosition.kt
│       │   │   ├── llm/
│       │   │   │   ├── LLMModel_extensions.kt
│       │   │   │   ├── LLMModel.kt
│       │   │   │   ├── LLMModelCapabilities.kt
│       │   │   │   ├── LLMModelType.kt
│       │   │   │   ├── LLMProvider.kt
│       │   │   │   ├── LLMProviderType.kt
│       │   │   │   └── ModelSettings.kt
│       │   │   ├── tool/
│       │   │   │   ├── LocalMCPToolDefinition.kt
│       │   │   │   ├── MiscToolDefinition.kt
│       │   │   │   ├── ToolCall.kt
│       │   │   │   ├── ToolCallStatus.kt
│       │   │   │   ├── ToolDefinition.kt
│       │   │   │   ├── ToolType.kt
│       │   │   │   └── UserToolApprovalPreference.kt
│       │   │   ├── user/
│       │   │   │   ├── Permission.kt
│       │   │   │   ├── Role.kt
│       │   │   │   ├── User.kt
│       │   │   │   ├── UserGroup.kt
│       │   │   │   ├── UserStatus.kt
│       │   │   │   └── UserWithDetails.kt
│       │   │   └── worker/
│       │   │       └── WorkerDto.kt
│       │   └── security/
│       │       ├── error/
│       │       │   └── PasswordValidationError.kt
│       │       ├── CryptoError.kt
│       │       ├── CryptoProvider.kt
│       │       ├── EncryptedSecret.kt
│       │       ├── EncryptionConfig.kt
│       │       ├── EncryptionService.kt
│       │       └── PasswordValidator.kt
│       ├── commonTest/kotlin/eu/torvian/chatbot/common/
│       │   ├── api/resources/
│       │   │   └── WsResourceTest.kt
│       │   └── models/api/
│       │       ├── mcp/
│       │       │   └── LocalMCPServerDtosTest.kt
│       │       └── worker/protocol/
│       │           ├── codec/
│       │           │   └── WorkerProtocolPayloadCodecTest.kt
│       │           └── mapping/
│       │               ├── WorkerMcpServerControlProtocolMappingsTest.kt
│       │               └── WorkerMcpToolCallProtocolMappingsTest.kt
│       ├── desktopAndroidMain/kotlin/eu/torvian/chatbot/common/security/
│       │   └── AESCryptoProvider.kt
│       ├── desktopTest/kotlin/eu/torvian/chatbot/common/security/
│       │   ├── AESCryptoProviderTest.kt
│       │   └── EncryptionServiceTest.kt
│       ├── wasmJsMain/kotlin/eu/torvian/chatbot/common/security/
│       │   └── WasmJsWebCryptoProvider.kt
│       └── wasmJsTest/kotlin/eu/torvian/chatbot/common/security/
│           └── WasmJsWebCryptoProviderTest.kt
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```