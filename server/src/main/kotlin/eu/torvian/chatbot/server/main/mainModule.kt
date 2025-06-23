package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.models.LLMProviderType
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategy
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientKtor
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Defines the Koin module for components specific to the main application setup.
 * @param application The Ktor Application instance to provide.
 */
fun mainModule(application: Application) = module {
    single { application }
    single<DataManager> { ExposedDataManager(get()) }

    // --- External Services ---
    single<LLMApiClient> { LLMApiClientKtor(get(), get()) }

    // --- JSON Serializer ---
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    // --- HTTP Client ---
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
}
