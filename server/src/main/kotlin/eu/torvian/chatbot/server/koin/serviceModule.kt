package eu.torvian.chatbot.server.koin

import eu.torvian.chatbot.common.models.LLMProviderType
import eu.torvian.chatbot.server.service.core.*
import eu.torvian.chatbot.server.service.core.impl.*
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientKtor
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategy
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
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
    // --- Core Services ---
    single<SessionService> { SessionServiceImpl(get(), get()) }
    single<GroupService> { GroupServiceImpl(get(), get(), get()) }
    single<LLMModelService> { LLMModelServiceImpl(get(), get(), get()) }
    single<ModelSettingsService> { ModelSettingsServiceImpl(get(), get()) }
    single<LLMProviderService> { LLMProviderServiceImpl(get(), get(), get(), get()) }
    single<MessageService> { MessageServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) }

    // --- Security Services ---
    single<CryptoProvider> { AESCryptoProvider(get()) }
    single<EncryptionService> { EncryptionService(get()) }
    single<CredentialManager> { DbEncryptedCredentialManager(get(), get()) }

    // --- External Services ---
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    single<HttpClient> {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(get<Json>())
            }
        }
    }

    // --- LLM Strategies ---
    single<OpenAIChatStrategy> { OpenAIChatStrategy(get()) }

    single<Map<LLMProviderType, ChatCompletionStrategy>> {
        mapOf(
            LLMProviderType.OPENAI to get<OpenAIChatStrategy>(),
            LLMProviderType.OPENROUTER to get<OpenAIChatStrategy>(), // OpenRouter uses OpenAI-compatible API
            // Add other strategies here as they are implemented
            // LLMProviderType.ANTHROPIC to get<AnthropicChatStrategy>(),
            // LLMProviderType.OLLAMA to get<OllamaChatStrategy>(),
        )
    }

    single<LLMApiClient> { LLMApiClientKtor(get(), get()) }

   }
