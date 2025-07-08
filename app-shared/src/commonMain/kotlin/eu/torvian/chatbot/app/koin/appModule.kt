package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.GroupApi
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.app.service.api.ktor.KtorChatApiClient
import eu.torvian.chatbot.app.service.api.ktor.KtorGroupApiClient
import eu.torvian.chatbot.app.service.api.ktor.KtorModelApiClient
import eu.torvian.chatbot.app.service.api.ktor.KtorProviderApiClient
import eu.torvian.chatbot.app.service.api.ktor.KtorSessionApiClient
import eu.torvian.chatbot.app.service.api.ktor.KtorSettingsApiClient
import eu.torvian.chatbot.app.service.api.ktor.createHttpClient
import io.ktor.client.*
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
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

    // Provide ViewModel instances using the ViewModel factory and injecting dependencies via Koin
    // These will be retrieved in Composables using viewModel { get() }
    // See PR 14 for ViewModel implementation
    // viewModel { ChatState(get(), get()) } // Need ChatApi and SessionApi
    // viewModel { SessionListState(get(), get()) } // Need SessionApi and GroupApi
    // ... add bindings for other ViewModels (SettingsState, etc.)
}