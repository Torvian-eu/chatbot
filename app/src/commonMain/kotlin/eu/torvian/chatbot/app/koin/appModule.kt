package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.repository.impl.*
import eu.torvian.chatbot.app.service.api.*
import eu.torvian.chatbot.app.service.api.ktor.*
import eu.torvian.chatbot.app.service.auth.createAuthenticatedHttpClient
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManager
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManagerImpl
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.service.security.CertificateTrustService
import eu.torvian.chatbot.app.viewmodel.LocalMCPServerViewModel
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.app.viewmodel.ModelSettingsViewModel
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.admin.UserGroupManagementViewModel
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
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Koin module for providing dependencies related to the application's frontend.
 *
 * This module includes:
 * - Two Ktor [HttpClient] instances: authenticated and unauthenticated
 * - API client implementations for each backend API
 * - Authentication components (TokenStorage, AuthApi, AuthRepository)
 * - ViewModels for managing the application's state
 *
 * @param config The application configuration containing server URL and other settings.
 * @return A Koin module with frontend dependencies
 */
fun appModule(config: AppConfiguration): Module = module {
    // Provide application config
    single<AppConfiguration> { config }

    // Provide JSON serializer singleton
    single<Json> { Json }

    // Provide CertificateTrustService singleton for certificate trust decisions
    single<CertificateTrustService> {
        CertificateTrustService()
    }

    // Provide the unauthenticated Ktor HttpClient for auth operations
    single<HttpClient>(named("unauthenticated")) {
        createPlatformHttpClient(
            baseUri = config.network.serverUrl,
            json = get(),
            logLevel = LogLevel.INFO,
            certificateStorage = get(),
            certificateTrustService = get()
        )
    }

    // Provide the authenticated Ktor HttpClient with Auth plugin
    single<HttpClient>(named("authenticated")) {
        createAuthenticatedHttpClient(
            baseClient = get(named("unauthenticated")),
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

    single<NotificationService> {
        NotificationService(get())
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
        KtorChatApiClient(
            client = get(),
            wss = config.network.serverUrl.startsWith("https"),
            webSocketAuthSubprotocolProvider = getOrNull()
        )
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
    single<UserGroupApi> {
        KtorUserGroupApiClient(get())
    }
    single<ToolApi> {
        KtorToolApiClient(get())
    }
    single<LocalMCPServerApi> {
        KtorLocalMCPServerApiClient(get())
    }
    single<LocalMCPToolApi> {
        KtorLocalMCPToolApiClient(get())
    }
    single<WorkerApi> {
        KtorWorkerApiClient(get())
    }

    // Provide Repository implementations, injecting the API clients
    single<ModelRepository> {
        DefaultModelRepository(get())
    }
    single<ProviderRepository> {
        DefaultProviderRepository(get())
    }
    single<ModelSettingsRepository> {
        DefaultModelSettingsRepository(get())
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
    single<UserGroupRepository> {
        DefaultUserGroupRepository(get())
    }
    single<ToolRepository> {
        DefaultToolRepository(get())
    }
    single<LocalMCPServerRepository> {
        DefaultLocalMCPServerRepository(
            api = get()
        )
    }
    single<LocalMCPServerRuntimeStatusRepository> {
        DefaultLocalMCPServerRuntimeStatusRepository(
            api = get()
        )
    }
    single<WorkerRepository> {
        DefaultWorkerRepository(get())
    }
    single<LocalMCPToolRepository> {
        DefaultLocalMCPToolRepository(
            localMCPToolApi = get(),
            toolRepository = get()
        )
    }

    single<LocalMCPServerManager> {
        LocalMCPServerManagerImpl(
            serverRepository = get(),
            runtimeStatusRepository = get(),
            toolRepository = get()
        )
    }

    // Provide shared chat state with background scope for computed state flows
    factory<ChatState> { (backgroundScope: CoroutineScope) ->
        ChatStateImpl(
            sessionRepository = get(),
            modelSettingsRepository = get(),
            modelRepository = get(),
            toolRepository = get(),
            mcpServerRepository = get(),
            threadBuilder = get(),
            backgroundScope = backgroundScope
        )
    }

    // Provide use cases with updated dependencies (now using repositories)
    factory<LoadSessionUseCase> { (chatState: ChatState, backgroundScope: CoroutineScope) ->
        LoadSessionUseCase(
            get<SessionRepository>(),
            get<ModelSettingsRepository>(),
            get<ModelRepository>(),
            get<ToolRepository>(),
            get<LocalMCPServerRepository>(),
            chatState,
            get(),
            get(),
            backgroundScope
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

    factory { (chatState: ChatState) ->
        InsertMessageUseCase(chatState, get(), get())
    }

    factory<CopyToClipboardUseCase> { (chatState: ChatState) ->
        CopyToClipboardUseCase(chatState, get(), get())
    }

    factory<ToggleToolsUseCase> { (chatState: ChatState) ->
        ToggleToolsUseCase(chatState, get(), get())
    }

    factory<FileReferenceUseCase> { (chatState: ChatState, scope: CoroutineScope) ->
        FileReferenceUseCase(chatState, get(), scope)
    }

    // Provide ViewModels, injecting the required dependencies
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        val backgroundScope = scopeProvider.createBackgroundScope()
        val chatState = get<ChatState> { parametersOf(backgroundScope) }

        ChatViewModel(
            state = chatState,
            loadSessionUC = get { parametersOf(chatState, backgroundScope) },
            sendMessageUC = get { parametersOf(chatState) },
            replyUC = get { parametersOf(chatState) },
            editMessageUC = get { parametersOf(chatState) },
            deleteMessageUC = get { parametersOf(chatState) },
            insertMessageUC = get { parametersOf(chatState) },
            switchBranchUC = get { parametersOf(chatState) },
            selectModelUC = get { parametersOf(chatState) },
            selectSettingsUC = get { parametersOf(chatState) },
            updateInputUC = get { parametersOf(chatState) },
            copyToClipboardUC = get { parametersOf(chatState) },
            toggleToolsUC = get { parametersOf(chatState) },
            fileReferenceUC = get { parametersOf(chatState, normalScope) },
            normalScope = normalScope,
            backgroundScope = backgroundScope
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        AuthViewModel(get<AuthRepository>(), get<NotificationService>(), normalScope)
    }
    viewModel { SessionListViewModel(get<SessionRepository>(), get<GroupRepository>(), get<EventBus>(), get()) }
    viewModel {
        ProviderConfigViewModel(
            get<ProviderRepository>(),
            get<UserGroupRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        ModelConfigViewModel(
            get<ModelRepository>(),
            get<ProviderRepository>(),
            get<UserGroupRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        ModelSettingsViewModel(
            get<ModelSettingsRepository>(),
            get<ModelRepository>(),
            get<UserGroupRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        val scopeProvider = get<CoroutineScopeProvider>()
        val normalScope = scopeProvider.createNormalScope()
        UserManagementViewModel(get<UserRepository>(), get<RoleRepository>(), get<NotificationService>(), normalScope)
    }
    viewModel {
        UserGroupManagementViewModel(
            get<UserGroupRepository>(),
            get<UserRepository>(),
            get<NotificationService>()
        )
    }
    viewModel {
        LocalMCPServerViewModel(
            serverManager = get(),
            mcpToolRepository = get(),
            toolRepository = get(),
            workerRepository = get(),
            notificationService = get()
        )
    }
}
