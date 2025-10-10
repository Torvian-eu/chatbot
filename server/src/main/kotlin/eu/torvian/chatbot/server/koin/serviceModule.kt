package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.security.AESCryptoProvider
import eu.torvian.chatbot.common.security.CryptoProvider
import eu.torvian.chatbot.common.security.EncryptionService
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.security.*
import eu.torvian.chatbot.server.service.security.authorizer.GroupResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.ModelResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.ProviderResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.ResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.SessionResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.SettingsResourceAuthorizer
import eu.torvian.chatbot.server.service.setup.InitialSetupService
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's service layer.
 *
 * This module provides:
 * - Core Servics (session, group, model, settings, message, LLM provider)
 * - Security services (credential management, encryption)
 */
fun serviceModule() = module {
    // --- Core Services ---
    single<SessionService> { SessionServiceImpl(get(), get(), get(), get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get(), get(), get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<RoleService> { RoleServiceImpl(get(), get(), get()) }
    single<UserGroupService> { UserGroupServiceImpl(get(), get(), get()) }

    // --- Security Services ---
    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }

    // --- Authentication Services ---
    single<PasswordService> { BCryptPasswordService() }
    single<UserService> { UserServiceImpl(get(), get(), get(), get(), get(), get()) }
    single<AuthenticationService> { AuthenticationServiceImpl(get(), get(), get(), get(), get(), get(), get()) }

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
    single<AuthorizationService> { AuthorizationServiceImpl(getAll<ResourceAuthorizer>().associateBy { it.resourceType }, get(), get(), get()) }

    // --- Setup Services ---
    single<InitialSetupService> { InitialSetupService(get(), get(), get()) }

}
