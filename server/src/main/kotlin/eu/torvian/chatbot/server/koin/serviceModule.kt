package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.models.tool.ToolNameSanitizer
import eu.torvian.chatbot.common.models.tool.ToolNameValidator
import eu.torvian.chatbot.common.models.tool.ToolNamePrefixValidator
import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionService
import eu.torvian.chatbot.common.security.PasswordValidator
import eu.torvian.chatbot.server.config.AppConfiguration
import eu.torvian.chatbot.server.service.builtin.BuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.builtin.DefaultBuiltInWorkerToolExecutor
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.chat.content.DefaultFileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.DefaultToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.FileReferenceContentBuilder
import eu.torvian.chatbot.server.service.core.chat.content.ToolResultContentBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.DefaultChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.persistence.DefaultConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.preparation.DefaultConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.turn.ConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.chat.turn.DefaultConversationTurnOrchestrator
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.core.toolcall.DefaultToolCallOrchestrator
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallOrchestrator
import eu.torvian.chatbot.server.service.email.LoggingMailService
import eu.torvian.chatbot.server.service.email.MailService
import eu.torvian.chatbot.server.service.email.SmtpMailService
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.security.*
import eu.torvian.chatbot.server.service.security.authorizer.*
import eu.torvian.chatbot.server.service.setup.InitializationCoordinator
import eu.torvian.chatbot.server.service.setup.UserAccountInitializer
import eu.torvian.chatbot.server.worker.builtin.BuiltInToolDispatchService
import eu.torvian.chatbot.server.worker.builtin.DefaultBuiltInToolDispatchService
import eu.torvian.chatbot.server.worker.mcp.configsync.DefaultLocalMCPServerConfigSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.DefaultLocalMCPServerWorkerSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerConfigSyncService
import eu.torvian.chatbot.server.worker.mcp.configsync.LocalMCPServerWorkerSyncService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.DefaultLocalMCPRuntimeCommandDispatchService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.DefaultLocalMCPRuntimeControlService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchService
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeControlService
import eu.torvian.chatbot.server.worker.mcp.toolcall.DefaultLocalMCPToolCallDispatchService
import eu.torvian.chatbot.server.worker.mcp.toolcall.LocalMCPToolCallDispatchService
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's service layer.
 */
fun serviceModule() = module {
    // --- Tool-name sanitization/validation (LLM-safe character set) ---
    single { ToolNameSanitizer() }
    single { ToolNameValidator() }
    single { ToolNamePrefixValidator() }

    single<SessionService> { SessionServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get()) }
    single<SearchService> { SearchServiceImpl(get()) }
    single<ToolCallOrchestrator> { DefaultToolCallOrchestrator(get(), get(), get()) }
    single<FileReferenceContentBuilder> { DefaultFileReferenceContentBuilder() }
    single<ToolResultContentBuilder> { DefaultToolResultContentBuilder() }
    single<ChatContextBuilder> { DefaultChatContextBuilder(get(), get()) }
    single<ConversationTurnPersistence> { DefaultConversationTurnPersistence(get(), get(), get(), get()) }
    single<ConversationTurnPreparationService> {
        DefaultConversationTurnPreparationService(get(), get(), get(), get(), get(), get(), get(), get())
    }
    single<ConversationTurnOrchestrator> {
        DefaultConversationTurnOrchestrator(get(), get(), get(), get(), get())
    }
    single<ChatService> { ChatServiceImpl(get(), get()) }
    single<ToolService> { ToolServiceImpl(get(), get(), get(), get(), get(), get()) }
    single<ToolCallService> { ToolCallServiceImpl(get(), get()) }
    single<LocalMCPServerService> { LocalMCPServerServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<LocalMCPRuntimeCommandDispatchService> { DefaultLocalMCPRuntimeCommandDispatchService(get()) }
    single<LocalMCPServerWorkerSyncService> { DefaultLocalMCPServerWorkerSyncService(get()) }
    single<LocalMCPRuntimeControlService> { DefaultLocalMCPRuntimeControlService(get(), get(), get()) }
    single<LocalMCPServerConfigSyncService> { DefaultLocalMCPServerConfigSyncService(get(), get()) }
    single<LocalMCPToolDefinitionService> { LocalMCPToolDefinitionServiceImpl(get(), get(), get(), get(), get()) }
    single<LocalMCPToolCallDispatchService> { DefaultLocalMCPToolCallDispatchService(get()) }
    single<LocalMCPExecutor> { LocalMCPExecutor(get(), get()) }

    // --- Built-in worker tool services (direct `tool.call` dispatch) ---
    single<BuiltInToolDispatchService> { DefaultBuiltInToolDispatchService(get()) }
    single<BuiltInWorkerToolExecutor> { DefaultBuiltInWorkerToolExecutor(get()) }
    single<BuiltInToolDefinitionSeeder> { BuiltInToolDefinitionSeeder(get(), get(), get()) }
    single<BuiltInToolDefinitionService> {
        BuiltInToolDefinitionServiceImpl(
            workerDao = get(),
            builtInToolDefinitionDao = get(),
            builtInToolDefinitionSeeder = get(),
            toolService = get(),
            transactionScope = get()
        )
    }

    single<RoleService> { RoleServiceImpl(get(), get(), get()) }
    single<AgentRoleService> {
        AgentRoleServiceImpl(
            agentRoleDao = get(),
            agentRoleToolDao = get(),
            agentRoleOwnershipDao = get(),
            modelDao = get(),
            settingsDao = get(),
            toolDefinitionDao = get(),
            json = get(),
            transactionScope = get()
        )
    }
    single<UserGroupService> { UserGroupServiceImpl(get(), get(), get()) }
    single<UserPreferenceService> { UserPreferenceServiceImpl(get(), get(), get()) }

    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }
    single<CertificateService> { DefaultCertificateService() }

    single<MailService> {
        val config = get<AppConfiguration>()
        when (config.email.provider.lowercase()) {
            "smtp" -> SmtpMailService(
                fromAddress = config.email.fromAddress,
                properties = config.email.properties
            )

            else -> LoggingMailService(
                fromAddress = config.email.fromAddress
            )
        }
    }

    single<SecurityNotificationService> {
        SecurityNotificationServiceImpl(
            mailService = get(),
            serverUrl = get<AppConfiguration>().serverUrl
        )
    }

    single<PasswordService> {
        BCryptPasswordService(PasswordValidator(get<AppConfiguration>().authPolicy.passwordConfig))
    }
    single<UserService> { UserServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<TokenService> {
        TokenServiceImpl(
            userService = get(),
            jwtConfig = get(),
            userSessionDao = get(),
            workerDao = get(),
            authorizationService = get(),
            transactionScope = get()
        )
    }
    single<DeviceTrustService> {
        DeviceTrustServiceImpl(
            userDao = get(),
            userTrustedDeviceDao = get(),
            userSessionDao = get(),
            securityAuditDao = get(),
            deviceVerificationTokenDao = get(),
            securityNotificationService = get(),
            transactionScope = get()
        )
    }
    single<SecurityAuditService> {
        SecurityAuditServiceImpl(
            securityAuditDao = get(),
            userTrustedDeviceDao = get(),
            userSessionDao = get(),
            transactionScope = get()
        )
    }
    single<AccountManagementService> {
        AccountManagementServiceImpl(
            userDao = get(),
            passwordService = get(),
            transactionScope = get()
        )
    }
    single<AuthenticationService> {
        AuthenticationServiceImpl(
            userService = get(),
            passwordService = get(),
            jwtConfig = get(),
            userSessionDao = get(),
            userTrustedDeviceDao = get(),
            userDeviceDao = get(),
            securityAuditDao = get(),
            userDao = get(),
            authorizationService = get(),
            transactionScope = get(),
            accountSecurityMode = get(),
            failedLoginAttemptDao = get(),
            authPolicy = get()
        )
    }
    single<WorkerService> { WorkerServiceImpl(get(), get(), get(), get()) }

    single<ResourceAuthorizer>(named(ResourceType.GROUP.key)) { GroupResourceAuthorizer(get()) }
    single<ResourceAuthorizer>(named(ResourceType.SESSION.key)) { SessionResourceAuthorizer(get()) }
    single<ResourceAuthorizer>(named(ResourceType.PROVIDER.key)) {
        ProviderResourceAuthorizer(get(), get(), get())
    }
    single<ResourceAuthorizer>(named(ResourceType.MODEL.key)) {
        ModelResourceAuthorizer(get(), get(), get())
    }
    single<ResourceAuthorizer>(named(ResourceType.SETTINGS.key)) {
        SettingsResourceAuthorizer(get(), get(), get())
    }

    single<AuthorizationService> {
        AuthorizationServiceImpl(
            getAll<ResourceAuthorizer>().associateBy { it.resourceType },
            get(),
            get(),
            get()
        )
    }

    single<UserAccountInitializer> { UserAccountInitializer(get(), get(), get()) }
    single<InitializationCoordinator> {
        InitializationCoordinator(
            listOf(get<UserAccountInitializer>())
        )
    }
}
