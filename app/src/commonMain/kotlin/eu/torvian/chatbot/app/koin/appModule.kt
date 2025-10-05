package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.repository.impl.*
import eu.torvian.chatbot.app.service.api.*
import eu.torvian.chatbot.app.service.api.ktor.*
import eu.torvian.chatbot.app.service.auth.createAuthenticatedHttpClient
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.SettingsConfigViewModel
import eu.torvian.chatbot.app.viewmodel.admin.UserManagementViewModel
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatStateImpl
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
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
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for providing dependencies related to the application's frontend.
 *
 * This module includes:
 * - Two Ktor [HttpClient] instances: authenticated and unauthenticated
 * - API client implementations for each backend API
 * - Authentication components (TokenStorage, AuthApi, AuthRepository)
 * - ViewModels for managing the application's state
 *
 * @param baseUri The base URI for the API endpoint
 * @return A Koin module with frontend dependencies
 */
fun appModule(baseUri: String): Module = module {
    // Provide the unauthenticated Ktor HttpClient for auth operations
    single<HttpClient>(named("unauthenticated")) {
        createHttpClient(baseUri, Json)
    }

    // Provide the authenticated Ktor HttpClient with Auth plugin
    single<HttpClient>(named("authenticated")) {
        createAuthenticatedHttpClient(
            baseUri = baseUri,
            json = Json,
            tokenStorage = get(),
            unauthenticatedHttpClient = get(named("unauthenticated")),
            eventBus = get()
        )
    }

    // Create AuthApi with both authenticated and unauthenticated clients
    single<AuthApi> {
        KtorAuthApiClient(
            unauthenticatedClient = get(named("unauthenticated")),
            authenticatedClient = get(named("authenticated"))
        )
    }

    single<AuthRepository> {
        DefaultAuthRepository(
            authApi = get(),
            userApi = get(),
            tokenStorage = get(),
            eventBus = get()
        )
    }

    // Default HttpClient (authenticated) for backward compatibility
    single<HttpClient> {
        get<HttpClient>(named("authenticated"))
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
    single<UserApi> {
        KtorUserApiClient(get())
    }
    single<RoleApi> {
        KtorRoleApiClient(get())
    }

    // Provide Repository implementations, injecting the API clients
    single<ModelRepository> {
        DefaultModelRepository(get())
    }
    single<ProviderRepository> {
        DefaultProviderRepository(get())
    }
    single<SettingsRepository> {
        DefaultSettingsRepository(get())
    }
    single<SessionRepository> {
        DefaultSessionRepository(get(), get())
    }
    single<GroupRepository> {
        DefaultGroupRepository(get())
    }
    single<UserRepository> {
        DefaultUserRepository(get())
    }
    single<RoleRepository> {
        DefaultRoleRepository(get())
    }

    // Provide shared chat state with background scope for computed state flows
    factory<ChatState> { (backgroundScope: CoroutineScope) ->
        ChatStateImpl(
            sessionRepository = get(),
            settingsRepository = get(),
            modelRepository = get(),
            threadBuilder = get(),
            backgroundScope = backgroundScope
        )
    }


    // Provide use cases with updated dependencies (now using repositories)
    factory<LoadSessionUseCase> { (chatState: ChatState) ->
        LoadSessionUseCase(
            get<SessionRepository>(),
            get<SettingsRepository>(),
            get<ModelRepository>(),
            chatState,
            get()
        )
    }

    factory<UpdateInputUseCase> { (chatState: ChatState) ->
        UpdateInputUseCase(chatState)
    }

    factory<ReplyUseCase> { (chatState: ChatState) ->
        ReplyUseCase(chatState)
    }

    factory<SelectModelUseCase> { (chatState: ChatState) ->
        SelectModelUseCase(get<SessionRepository>(), chatState, get())
    }

    factory<SelectSettingsUseCase> { (chatState: ChatState) ->
        SelectSettingsUseCase(get<SessionRepository>(), chatState, get())
    }

    factory<SwitchBranchUseCase> { (chatState: ChatState) ->
        SwitchBranchUseCase(get<SessionRepository>(), get(), chatState, get())
    }

    factory<SendMessageUseCase> { (chatState: ChatState) ->
        SendMessageUseCase(get<SessionRepository>(), chatState, get())
    }

    factory<EditMessageUseCase> { (chatState: ChatState) ->
        EditMessageUseCase(get<SessionRepository>(), chatState, get())
    }

    factory<DeleteMessageUseCase> { (chatState: ChatState) ->
        DeleteMessageUseCase(get<SessionRepository>(), chatState, get())
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
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        AuthViewModel(get<AuthRepository>(), get<ErrorNotifier>(), normalScope)
    }
    viewModel { SessionListViewModel(get<SessionRepository>(), get<GroupRepository>(), get<EventBus>(), get()) }
    viewModel { ProviderConfigViewModel(get<ProviderRepository>(), get<ErrorNotifier>()) }
    viewModel { ModelConfigViewModel(get<ModelRepository>(), get<ProviderRepository>(), get<ErrorNotifier>()) }
    viewModel { SettingsConfigViewModel(get<SettingsRepository>(), get<ModelRepository>(), get<ErrorNotifier>()) }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        UserManagementViewModel(
            userRepository = get(),
            roleRepository = get(),
            errorNotifier = get(),
            normalScope = normalScope
        )
    }
}
