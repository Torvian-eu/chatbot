package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.repository.impl.*
import eu.torvian.chatbot.app.repository.impl.session.SessionDetailsStore
import eu.torvian.chatbot.app.repository.impl.session.SessionEventApplier
import eu.torvian.chatbot.app.repository.impl.session.SessionSummaryStore
import eu.torvian.chatbot.app.repository.impl.session.ToolCallStore
import eu.torvian.chatbot.app.service.api.*
import eu.torvian.chatbot.app.service.api.ktor.*
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module providing the backend API clients and the repositories built on top of them.
 *
 * Every client binds its interface to a Ktor implementation sharing the application's HTTP client,
 * and every repository binds its interface to the default implementation backed by the matching
 * client.
 *
 * @param config The application configuration; the chat client reads the server URL to decide
 *   whether the chat socket runs over TLS.
 * @return A Koin module with the backend access layer.
 */
fun appApiModule(config: AppConfiguration): Module = module {
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
    single<SearchApi> {
        KtorSearchApiClient(get())
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
    single<AgentRoleApi> {
        KtorAgentRoleApiClient(get())
    }
    single<ProjectApi> {
        KtorProjectApiClient(get())
    }
    single<ModelPresetApi> {
        KtorModelPresetApiClient(get())
    }
    single<InstructionApi> {
        KtorInstructionApiClient(get())
    }
    single<UserGroupApi> {
        KtorUserGroupApiClient(get())
    }
    single<ToolApi> {
        KtorToolApiClient(get())
    }
    single<LocalMCPServerApi> {
        KtorLocalMCPServerApiClient(
            client = get(),
            json = get(),
            requestSigningService = get()
        )
    }
    single<LocalMCPToolApi> {
        KtorLocalMCPToolApiClient(get())
    }
    single<CredentialApi> {
        KtorCredentialApiClient(
            client = get(),
            json = get(),
            requestSigningService = get()
        )
    }
    single<BuiltInToolApi> {
        KtorBuiltInToolApiClient(get())
    }
    single<OperatorToolApi> {
        KtorOperatorToolApiClient(get())
    }
    single<ServerBuiltInToolApi> {
        KtorServerBuiltInToolApiClient(get())
    }
    single<WorkerApi> {
        KtorWorkerApiClient(get())
    }
    single<UserPreferenceApi> {
        KtorUserPreferenceApiClient(get())
    }
    single<MetadataApi> {
        KtorMetadataApiClient(get(named("unauthenticated")))
    }

    // Provide Repository implementations, injecting the API clients
    single<ModelRepository> {
        DefaultModelRepository(get())
    }
    single<UserPreferenceRepository> {
        DefaultUserPreferenceRepository(get())
    }
    single<ProviderRepository> {
        DefaultProviderRepository(get())
    }
    single<ModelSettingsRepository> {
        DefaultModelSettingsRepository(get())
    }
    // The session API is split into the list half and the conversation half, which share four collaborators. Each
    // collaborator is created once here, so the cached rows, the cached transcripts and the cached tool calls have a
    // single owner for the whole application.
    single { SessionDetailsStore() }
    single { ToolCallStore() }
    single { SessionSummaryStore() }
    single { SessionEventApplier(details = get(), toolCalls = get()) }
    single<SessionRepository> {
        DefaultSessionListRepository(
            sessionApi = get(),
            summaryStore = get(),
            details = get()
        )
    }
    single<ConversationRepository> {
        DefaultConversationRepository(
            sessionApi = get(),
            chatApi = get(),
            details = get(),
            toolCalls = get(),
            eventApplier = get(),
            summaryStore = get()
        )
    }
    single<GroupRepository> {
        DefaultGroupRepository(get())
    }
    single<SearchRepository> {
        DefaultSearchRepository(get())
    }
    single<UserRepository> {
        DefaultUserRepository(get())
    }
    single<RoleRepository> {
        DefaultRoleRepository(get())
    }
    single<AgentRoleRepository> {
        DefaultAgentRoleRepository(
            agentRoleApi = get(),
            projectRepository = get(),
            // The two instruction-link operations are role mutations; their HTTP methods live on the
            // instruction client because one side of the pair is an instruction row.
            instructionApi = get()
        )
    }
    single<ProjectRepository> {
        DefaultProjectRepository(get())
    }
    single<ModelPresetRepository> {
        // Preset mutations change the derived model/settings of bound roles, so the implementation
        // refreshes the role stream itself; the dependency direction is presets → roles → projects
        // and stays acyclic.
        DefaultModelPresetRepository(
            presetApi = get(),
            agentRoleRepository = get()
        )
    }
    single<InstructionRepository> {
        DefaultInstructionRepository(get())
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
    single<CredentialRepository> {
        DefaultCredentialRepository(api = get())
    }
    single<WorkerRepository> {
        DefaultWorkerRepository(
            workerApi = get(),
            toolRepository = get(),
            builtInToolRepository = get()
        )
    }
    single<LocalMCPToolRepository> {
        DefaultLocalMCPToolRepository(
            localMCPToolApi = get(),
            toolRepository = get()
        )
    }
    single<BuiltInToolRepository> {
        DefaultBuiltInToolRepository(
            builtInToolApi = get(),
            toolRepository = get()
        )
    }
    single<OperatorToolRepository> {
        DefaultOperatorToolRepository(
            operatorToolApi = get(),
            toolRepository = get()
        )
    }
    single<ServerBuiltInToolRepository> {
        DefaultServerBuiltInToolRepository(
            serverBuiltInToolApi = get(),
            toolRepository = get()
        )
    }
    single<ServerMetadataRepository> {
        DefaultServerMetadataRepository(get())
    }
}
