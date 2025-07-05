package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.service.apiclient.ktor.createHttpClient
import io.ktor.client.*
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
        createHttpClient(baseUri)
    }

//    // Provide concrete API client implementations, injecting the HttpClient
//    single<ChatApi> {
//        KtorChatApiClient(get()) // KtorChatApiClient needs to inherit from BaseApiClient
//    }
//    single<SessionApi> {
//        KtorSessionApiClient(get()) // KtorSessionApiClient needs to inherit from BaseApiClient
//    }
    // ... add bindings for GroupApi, ProviderApi, ModelApi, SettingsApi clients

    // Provide ViewModel instances using the ViewModel factory and injecting dependencies via Koin
    // These will be retrieved in Composables using viewModel { get() }
    // See PR 14 for ViewModel implementation
    // viewModel { ChatState(get(), get()) } // Need ChatApi and SessionApi
    // viewModel { SessionListState(get(), get()) } // Need SessionApi and GroupApi
    // ... add bindings for other ViewModels (SettingsState, etc.)
}