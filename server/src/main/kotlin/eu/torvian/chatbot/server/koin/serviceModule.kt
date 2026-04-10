package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionService
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.mcp.LocalMCPExecutor
import eu.torvian.chatbot.server.service.security.*
import eu.torvian.chatbot.server.service.security.authorizer.*
import eu.torvian.chatbot.server.service.setup.*
import eu.torvian.chatbot.server.service.tool.ToolExecutorFactory
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's service layer.
 *
 * This module provides:
 * - Core Services (session, group, model, settings, message, LLM provider, tool)
 * - Security services (credential management, encryption)
 * - Tool execution services
 */
fun serviceModule() = module {
    // --- Core Services ---
    single<SessionService> { SessionServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get()) }
    single<ChatService> { ChatServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single<ToolService> { ToolServiceImpl(get(), get(), get(), get(), get()) }
    single<ToolCallService> { ToolCallServiceImpl(get(), get()) }
    single<LocalMCPServerService> { LocalMCPServerServiceImpl(get(), get(), get()) }
    single<LocalMCPToolDefinitionService> { LocalMCPToolDefinitionServiceImpl(get(), get(), get(), get(), get()) }
    single<LocalMCPExecutor> { LocalMCPExecutor() }

    single<RoleService> { RoleServiceImpl(get(), get(), get()) }
    single<UserGroupService> { UserGroupServiceImpl(get(), get(), get()) }

    // --- Security Services ---
    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }
    single<CertificateService> { DefaultCertificateService() }

    // --- Authentication Services ---
    single<PasswordService> { BCryptPasswordService() }
    single<UserService> { UserServiceImpl(get(), get(), get(), get(), get(), get()) }
    single<AuthenticationService> { AuthenticationServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<WorkerService> { WorkerServiceImpl(get(), get(), get()) }

    // --- Authorizers (resource-level access) ---
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

    // --- Authorization Services ---
    single<AuthorizationService> {
        AuthorizationServiceImpl(
            getAll<ResourceAuthorizer>().associateBy { it.resourceType },
            get(),
            get(),
            get()
        )
    }

    // --- Setup Services ---
    // Individual initializers
    single<UserAccountInitializer> { UserAccountInitializer(get(), get(), get()) }
    single<ToolDefinitionInitializer> { ToolDefinitionInitializer(get(), get(), get()) }

    // Initialization coordinator that runs all initializers
    single<InitializationCoordinator> {
        InitializationCoordinator(
            listOf(
                get<UserAccountInitializer>(),
                get<ToolDefinitionInitializer>()
            )
        )
    }


    // --- Tool Executors ---
    // Add more executors as they are implemented:
    // single<CalculatorToolExecutor> { CalculatorToolExecutor() }

    // --- Tool Executor Factory ---
    single<ToolExecutorFactory> {
        ToolExecutorFactory(
            webSearchExecutor = get(named("web_search")),
            weatherExecutor = get(named("weather"))
            // Add more executors here as they are implemented
        )
    }
}
