package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.models.llm.LLMProviderType
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
import eu.torvian.chatbot.server.service.builtin.DefaultOperatorToolExecutor
import eu.torvian.chatbot.server.service.builtin.DefaultServerBuiltInToolExecutor
import eu.torvian.chatbot.server.service.builtin.OperatorToolExecutor
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInTool
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolExecutor
import eu.torvian.chatbot.server.service.builtin.tools.CreateAgentRoleTool
import eu.torvian.chatbot.server.service.builtin.tools.EditAgentRoleInstructionsTool
import eu.torvian.chatbot.server.service.builtin.tools.InsertAgentRoleInstructionTool
import eu.torvian.chatbot.server.service.builtin.tools.ListAgentRolesTool
import eu.torvian.chatbot.server.service.builtin.tools.ListModelSettingsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListModelsTool
import eu.torvian.chatbot.server.service.builtin.tools.ListToolsTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadAgentRoleTool
import eu.torvian.chatbot.server.service.builtin.tools.ReadToolTool
import eu.torvian.chatbot.server.service.builtin.tools.RemoveAgentRoleInstructionTool
import eu.torvian.chatbot.server.service.builtin.tools.UpdateAgentRoleTool
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.agent.AgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.agent.DefaultAgentSpawnRequestBuilder
import eu.torvian.chatbot.server.service.core.agent.DefaultSystemPromptComposer
import eu.torvian.chatbot.server.service.core.agent.SystemPromptComposer
import eu.torvian.chatbot.server.service.core.chat.compaction.ApproximateChatInputTokenCounter
import eu.torvian.chatbot.server.service.core.chat.compaction.ChatInputTokenCounter
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionConfigurationResolver
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionConfigurationService
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationCompactionConfigurationResolver
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationCompactionConfigurationService
import eu.torvian.chatbot.server.service.core.chat.compaction.DefaultConversationCompactionService
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
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategyResolver
import eu.torvian.chatbot.server.service.llm.DefaultReasoningCapabilityRecorder
import eu.torvian.chatbot.server.service.llm.ReasoningCapabilityRecorder
import eu.torvian.chatbot.server.service.llm.strategy.OllamaChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.ResponsesStrategy
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
    // --- Chat completion strategies and the shared dialect resolver ---
    // Bound here (rather than in mainModule) so the input token counter, the compaction resolver and
    // the test container resolve the identical dialect-selection rule as the HTTP client.
    single<OpenAIChatStrategy> { OpenAIChatStrategy(get()) }
    single<OllamaChatStrategy> { OllamaChatStrategy(get()) }
    single<ResponsesStrategy> { ResponsesStrategy(get()) }
    single<ChatCompletionStrategyResolver> {
        ChatCompletionStrategyResolver(
            strategies = mapOf(
                LLMProviderType.OPENAI to get<OpenAIChatStrategy>(),
                LLMProviderType.OPENROUTER to get<OpenAIChatStrategy>(),
                LLMProviderType.OLLAMA to get<OllamaChatStrategy>(),
            ),
            responsesStrategy = get<ResponsesStrategy>()
        )
    }

    // --- Tool-name sanitization/validation (LLM-safe character set) ---
    single { ToolNameSanitizer() }
    single { ToolNameValidator() }
    single { ToolNamePrefixValidator() }

    single<SessionService> { SessionServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get()) }
    single<SearchService> { SearchServiceImpl(get()) }
    single<ToolCallOrchestrator> {
        DefaultToolCallOrchestrator(get(), get(), get(), get(), get())
    }
    single<FileReferenceContentBuilder> { DefaultFileReferenceContentBuilder() }
    single<ToolResultContentBuilder> { DefaultToolResultContentBuilder() }
    single<ChatContextBuilder> { DefaultChatContextBuilder(get(), get()) }
    single<ConversationTurnPersistence> { DefaultConversationTurnPersistence(get(), get(), get(), get()) }
    single<ReasoningCapabilityRecorder> { DefaultReasoningCapabilityRecorder(get()) }
    single<ConversationTurnPreparationService> {
        DefaultConversationTurnPreparationService(
            messageDao = get(),
            sessionDao = get(),
            toolService = get(),
            llmModelService = get(),
            modelSettingsService = get(),
            llmProviderService = get(),
            credentialManager = get(),
            agentRoleService = get(),
            systemPromptComposer = get(),
            transactionScope = get()
        )
    }
    single<ConversationTurnOrchestrator> {
        DefaultConversationTurnOrchestrator(get(), get(), get(), get(), get(), get(), get())
    }
    // --- Automated conversation compaction ---
    single<ChatInputTokenCounter> {
        ApproximateChatInputTokenCounter(strategyResolver = get(), json = get())
    }
    single<ConversationCompactionConfigurationResolver> {
        DefaultConversationCompactionConfigurationResolver(
            llmModelService = get(),
            modelSettingsService = get(),
            llmProviderService = get(),
            credentialManager = get()
        )
    }
    single<ConversationCompactionConfigurationService> {
        DefaultConversationCompactionConfigurationService(
            json = get(),
            userPreferenceDao = get(),
            authorizationService = get(),
            modelSettingsService = get(),
            transactionScope = get()
        )
    }
    single<ConversationCompactionService> {
        DefaultConversationCompactionService(
            userPreferenceDao = get(),
            chunkDao = get(),
            configurationResolver = get(),
            tokenCounter = get(),
            llmApiClient = get(),
            json = get()
        )
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

    // --- Operator tool services (server-relayed, operator-executed) ---
    single<AgentSpawnRequestBuilder> { DefaultAgentSpawnRequestBuilder(get(), get()) }
    single<OperatorToolExecutor> { DefaultOperatorToolExecutor(get(), get()) }
    single<OperatorToolDefinitionSeeder> { OperatorToolDefinitionSeeder(get(), get(), get()) }
    single<OperatorToolDefinitionService> {
        OperatorToolDefinitionServiceImpl(
            operatorToolDefinitionDao = get(),
            operatorToolDefinitionSeeder = get(),
            toolService = get(),
            transactionScope = get()
        )
    }

    // --- Server built-in tool services (executed in-process on the server) ---
    single<Map<String, ServerBuiltInTool>> {
        // Registry of server built-in tools, keyed by catalog name (the executor dispatch key).
        // Keep this in sync with ServerBuiltInToolCatalog; each tool receives its own user-scoped
        // services via constructor injection (mirrors workerModule's BuiltInTool registry).
        listOf(
            ListAgentRolesTool(agentRoleService = get(), json = get()),
            ReadAgentRoleTool(agentRoleService = get(), json = get()),
            CreateAgentRoleTool(agentRoleService = get()),
            UpdateAgentRoleTool(agentRoleService = get()),
            InsertAgentRoleInstructionTool(agentRoleService = get()),
            EditAgentRoleInstructionsTool(agentRoleService = get()),
            RemoveAgentRoleInstructionTool(agentRoleService = get()),
            ListModelsTool(llmModelService = get(), json = get()),
            ListModelSettingsTool(llmModelService = get(), modelSettingsService = get(), json = get()),
            ListToolsTool(toolService = get(), json = get()),
            ReadToolTool(toolService = get(), json = get()),
        ).associateBy { it.name }
    }
    single<ServerBuiltInToolExecutor> {
        DefaultServerBuiltInToolExecutor(
            json = get(),
            tools = get()
        )
    }
    // Resolves the effective per-user prefix (global preference, else the hardcoded default). The
    // defaultPrefix constructor argument is intentionally omitted here: this binding is the single
    // swap point when a configurable server default (tools.builtInToolNamePrefix) lands later.
    single<ServerBuiltInToolNamePrefixResolver> {
        ServerBuiltInToolNamePrefixResolverImpl(
            userPreferenceDao = get()
        )
    }
    single<ServerBuiltInToolDefinitionSeeder> {
        ServerBuiltInToolDefinitionSeeder(get(), get(), get(), get())
    }
    single<ServerBuiltInToolDefinitionService> {
        ServerBuiltInToolDefinitionServiceImpl(
            serverBuiltInToolDefinitionDao = get(),
            serverBuiltInToolDefinitionSeeder = get(),
            toolService = get(),
            transactionScope = get()
        )
    }
    single<ServerBuiltInToolNamePrefixService> {
        ServerBuiltInToolNamePrefixServiceImpl(
            userPreferenceDao = get(),
            serverBuiltInToolDefinitionSeeder = get(),
            prefixResolver = get(),
            transactionScope = get()
        )
    }

    single<RoleService> { RoleServiceImpl(get(), get(), get()) }
    single<AgentRoleService> {
        AgentRoleServiceImpl(
            agentRoleDao = get(),
            agentRoleToolDao = get(),
            agentRoleSpawnableRoleDao = get(),
            agentRoleOwnershipDao = get(),
            modelDao = get(),
            settingsDao = get(),
            toolDefinitionDao = get(),
            json = get(),
            transactionScope = get()
        )
    }
    single<SystemPromptComposer> { DefaultSystemPromptComposer() }
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
    single<UserService> {
        UserServiceImpl(
            get(), get(), get(), get(), get(), get(), get(), get(), get()
        )
    }
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
            listOf(
                get<UserAccountInitializer>(),
                get<OperatorToolDefinitionSeeder>(),
                get<ServerBuiltInToolDefinitionSeeder>()
            )
        )
    }
}
