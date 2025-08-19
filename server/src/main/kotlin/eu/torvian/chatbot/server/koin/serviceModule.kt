package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.security.AESCryptoProvider
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.CryptoProvider
import eu.torvian.chatbot.server.service.security.DbEncryptedCredentialManager
import eu.torvian.chatbot.server.service.security.EncryptionService
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
    single<SessionService> { SessionServiceImpl(get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }

    // --- Security Services ---
    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }

}
