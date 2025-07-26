package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.service.api.*
import eu.torvian.chatbot.app.service.api.ktor.*
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.*
import io.ktor.client.*
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for providing dependencies related to the application's frontend.
 *
 * This module includes:
 * - The Ktor [HttpClient] configured for communication with the backend.
 * - API client implementations for each backend API.
 * - ViewModels for managing the application's state.
 *
 * @param baseUri The base URI for the API endpoint.
 * @return A Koin module with frontend dependencies.
 */
fun appModule(baseUri: String): Module = module {

    // Provide the Ktor HttpClient, configured with the base URI.
    single<HttpClient> {
        createHttpClient(baseUri, Json)
    }

    // Provide the EventBus for cross-cutting concerns like global events
    single<EventBus> {
        EventBus()
    }

    // Provide concrete API client implementations, injecting the HttpClient
    single<ChatApi> {
        KtorChatApiClient(get())
    }
    single<SessionApi> {
        KtorSessionApiClient(get())
    }
    single<GroupApi> {
        KtorGroupApiClient(get())
    }
    single<ModelApi> {
        KtorModelApiClient(get())
    }
    single<ProviderApi> {
        KtorProviderApiClient(get())
    }
    single<SettingsApi> {
        KtorSettingsApiClient(get())
    }

    // Provide ViewModels, injecting the required API clients
    viewModel { ChatViewModel(get(), get()) }
    viewModel { SessionListViewModel(get(), get(), get()) }
    viewModel { ProviderConfigViewModel(get()) }
    viewModel { ModelConfigViewModel(get(), get()) }
    viewModel { SettingsConfigViewModel(get(), get()) }
}