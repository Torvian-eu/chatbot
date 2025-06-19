package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientKtor
import eu.torvian.chatbot.server.service.security.AESCryptoProvider
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.service.security.CryptoProvider
import eu.torvian.chatbot.server.service.security.DbEncryptedCredentialManager
import eu.torvian.chatbot.server.service.security.EncryptionService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Dependency injection module for configuring the application's service layer.
 *
 * This module provides:
 * - Service interface implementations
 * - External service clients (LLM API client)
 * - Security services (credential management, encryption)
 */
fun serviceModule() = module {
    // --- Security Services ---
    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }

    // --- External Services ---
    single<HttpClient> {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
    single<LLMApiClient> { LLMApiClientKtor(get()) }

    // --- Service Layer ---
    single<SessionService> { SessionServiceImpl(get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
}
