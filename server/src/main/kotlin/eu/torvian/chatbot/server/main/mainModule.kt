package eu.torvian.chatbot.server.main

import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientKtor
import eu.torvian.chatbot.server.service.llm.discovery.OllamaModelDiscoveryStrategy
import eu.torvian.chatbot.server.service.llm.discovery.OpenAIModelDiscoveryStrategy
import eu.torvian.chatbot.server.service.llm.discovery.OpenRouterModelDiscoveryStrategy
import eu.torvian.chatbot.server.service.llm.strategy.OllamaChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
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
    single { DatabaseMigrator(get()) }

    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }

    single<HttpClient> {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(get<Json>())
            }
            install(HttpTimeout)
        }
    }

    single<OpenAIChatStrategy> { OpenAIChatStrategy(get()) }
    single<OllamaChatStrategy> { OllamaChatStrategy(get()) }
    single<OpenAIModelDiscoveryStrategy> { OpenAIModelDiscoveryStrategy(get()) }
    single<OllamaModelDiscoveryStrategy> { OllamaModelDiscoveryStrategy(get()) }
    single<OpenRouterModelDiscoveryStrategy> { OpenRouterModelDiscoveryStrategy(get()) }

    single<LLMApiClient> {
        val strategies = mapOf(
            LLMProviderType.OPENAI to get<OpenAIChatStrategy>(),
            LLMProviderType.OPENROUTER to get<OpenAIChatStrategy>(),
            LLMProviderType.OLLAMA to get<OllamaChatStrategy>(),
        )
        val modelDiscoveryStrategies = mapOf(
            LLMProviderType.OPENAI to get<OpenAIModelDiscoveryStrategy>(),
            LLMProviderType.OPENROUTER to get<OpenRouterModelDiscoveryStrategy>(),
            LLMProviderType.OLLAMA to get<OllamaModelDiscoveryStrategy>(),
        )
        LLMApiClientKtor(get(), strategies, modelDiscoveryStrategies)
    }
}
