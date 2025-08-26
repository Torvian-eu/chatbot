package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.service.api.*
import eu.torvian.chatbot.app.service.api.ktor.*
import eu.torvian.chatbot.app.service.misc.*
import eu.torvian.chatbot.app.viewmodel.*
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatStateImpl
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.app.viewmodel.common.CoroutineScopeProvider
import eu.torvian.chatbot.app.viewmodel.common.DefaultCoroutineScopeProvider
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
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

    // Provide supporting services
    single<Clock> {
        Clock.System
    }

    single<ErrorNotifier> {
        ErrorNotifier(get())
    }

    // Provide CoroutineScope factory for better testability
    single<CoroutineScopeProvider> {
        DefaultCoroutineScopeProvider()
    }

    // Provide thread building service
    single<ThreadBuilder> {
        DefaultThreadBuilder()
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

    // Provide shared chat state with background scope for computed state flows
    factory<ChatState> { (backgroundScope: CoroutineScope) ->
        ChatStateImpl(
            threadBuilder = get(),
            clock = get(),
            backgroundScope = backgroundScope
        )
    }


    // Provide use cases with updated dependencies (no more uiDispatcher parameters)
    factory<LoadSessionUseCase> { (chatState: ChatState) ->
        LoadSessionUseCase(get(), get(), get(), chatState, get())
    }

    factory<UpdateInputUseCase> { (chatState: ChatState) ->
        UpdateInputUseCase(chatState)
    }

    factory<ReplyUseCase> { (chatState: ChatState) ->
        ReplyUseCase(chatState)
    }

    factory<SelectModelUseCase> { (chatState: ChatState) ->
        SelectModelUseCase(get(), get(), chatState, get())
    }

    factory<SelectSettingsUseCase> { (chatState: ChatState) ->
        SelectSettingsUseCase(get(), get(), chatState, get())
    }

    factory<SwitchBranchUseCase> { (chatState: ChatState) ->
        SwitchBranchUseCase(get(), get(), chatState, get())
    }

    factory<StreamingCoordinator> { (chatState: ChatState) ->
        StreamingCoordinator(get(), chatState, get())
    }

    factory<SendMessageUseCase> { (chatState: ChatState) ->
        SendMessageUseCase(
            get(),
            chatState,
            get { parametersOf(chatState) },
            get()
        )
    }

    factory<EditMessageUseCase> { (chatState: ChatState) ->
        EditMessageUseCase(get(), chatState, get(), get())
    }

    factory<DeleteMessageUseCase> { (chatState: ChatState) ->
        DeleteMessageUseCase(get(), chatState, get { parametersOf(chatState) }, get())
    }

    // Provide ViewModels, injecting the required dependencies
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        val backgroundScope = scopeProvider.createBackgroundScope()
        val chatState = get<ChatState> { parametersOf(backgroundScope) }

        ChatViewModel(
            state = chatState,
            loadSessionUC = get { parametersOf(chatState) },
            sendMessageUC = get { parametersOf(chatState) },
            replyUC = get { parametersOf(chatState) },
            editMessageUC = get { parametersOf(chatState) },
            deleteMessageUC = get { parametersOf(chatState) },
            switchBranchUC = get { parametersOf(chatState) },
            selectModelUC = get { parametersOf(chatState) },
            selectSettingsUC = get { parametersOf(chatState) },
            updateInputUC = get { parametersOf(chatState) },
            eventBus = get(),
            normalScope = normalScope,
            backgroundScope = backgroundScope
        )
    }
    viewModel { SessionListViewModel(get(), get(), get()) }
    viewModel { ProviderConfigViewModel(get()) }
    viewModel { ModelConfigViewModel(get(), get()) }
    viewModel { SettingsConfigViewModel(get(), get()) }
}