package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.service.agent.AgentSpawnTool
import eu.torvian.chatbot.app.service.agent.DefaultOperatorToolExecutor
import eu.torvian.chatbot.app.service.agent.OperatorToolExecutor
import eu.torvian.chatbot.app.service.agent.SendMessageTool
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManager
import eu.torvian.chatbot.app.service.mcp.LocalMCPServerManagerImpl
import eu.torvian.chatbot.app.viewmodel.*
import eu.torvian.chatbot.app.viewmodel.chat.*
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatStateImpl
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module providing the chat runtime: shared chat state, the chat use cases, the session slot
 * and ViewModel resolution machinery used for spawned conversations, and the operator tool
 * infrastructure that lets one conversation drive another.
 *
 * @return A Koin module with the chat runtime dependencies.
 */
fun appChatModule(): Module = module {
    // Resolver that obtains a session's ChatViewModel (the same instance the UI resolves) from
    // regular code, so the spawn executor can drive a spawned session through its own ViewModel.
    // The root scope is the one the `viewModel` DSL definitions are installed into. The
    // ViewModelStoreOwner the spawned session must resolve against is NOT captured here: it is the
    // Chat destination's owner (the NavHost entry ChatScreen's koinViewModel resolves from), which
    // ChatScreen publishes into the provider during composition and the resolver reads at spawn time.
    single<ChatViewModelStoreOwnerProvider> { MutableChatViewModelStoreOwnerProvider() }
    single<SpawnedChatViewModelResolver> {
        KoinSpawnedChatViewModelResolver(
            slotManager = get(),
            scope = this,
            ownerProvider = get()
        )
    }

    // Coordinator for operator tools (spawn_agent, send_message): the single central operator-tool
    // executor, a router that dispatches each call to the per-tool OperatorTool registered for the
    // tool's catalog name (spawn creates the session, send targets an existing one). The target
    // conversation is driven through the session's own ChatViewModel and the result is reported
    // back on the original chat socket. The map mirrors the server's built-in tool registry style;
    // the keys are the catalog names the relay uses as the payload discriminator.
    single<OperatorToolExecutor> {
        DefaultOperatorToolExecutor(
            mapOf(
                OperatorToolCatalog.SPAWN_AGENT_NAME to AgentSpawnTool(
                    sessionRepository = get<SessionRepository>(),
                    authRepository = get<AuthRepository>(),
                    spawnedViewModelResolver = get<SpawnedChatViewModelResolver>()
                ),
                OperatorToolCatalog.SEND_MESSAGE_NAME to SendMessageTool(
                    authRepository = get<AuthRepository>(),
                    spawnedViewModelResolver = get<SpawnedChatViewModelResolver>()
                )
            )
        )
    }

    single<LocalMCPServerManager> {
        LocalMCPServerManagerImpl(
            serverRepository = get(),
            runtimeStatusRepository = get(),
            toolRepository = get(),
            workerRepository = get()
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
            agentRoleRepository = get(),
            projectRepository = get(),
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
            get<AgentRoleRepository>(),
            get<ProjectRepository>(),
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

    factory<SelectAgentRoleUseCase> { (chatState: ChatState) ->
        SelectAgentRoleUseCase(get<SessionRepository>(), chatState, get())
    }

    factory<LoadAgentRolesUseCase> { (_: ChatState) ->
        LoadAgentRolesUseCase(get<AgentRoleRepository>(), get())
    }

    factory<SelectProjectUseCase> { (chatState: ChatState) ->
        SelectProjectUseCase(get<SessionRepository>(), chatState, get())
    }

    factory<LoadProjectsUseCase> { (_: ChatState) ->
        LoadProjectsUseCase(get<ProjectRepository>(), get())
    }

    factory<SwitchBranchUseCase> { (chatState: ChatState) ->
        SwitchBranchUseCase(get<SessionRepository>(), get(), chatState, get())
    }

    factory<CompactConversationUseCase> { (chatState: ChatState, scope: CoroutineScope) ->
        CompactConversationUseCase(get<SessionRepository>(), chatState, get(), scope)
    }

    factory<SendMessageUseCase> { (chatState: ChatState) ->
        SendMessageUseCase(
            get<SessionRepository>(),
            get<ToolRepository>(),
            get(),
            get(),
            chatState,
            get(),
            get<SessionTurnStatusRegistry>()
        )
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

    factory<FileReferenceUseCase> { (chatState: ChatState, scope: CoroutineScope) ->
        FileReferenceUseCase(chatState, get(), scope)
    }

    // Provide the long-lived slot manager that maps sessions to ChatViewModel slots.
    single<ChatViewModelSlotManager> {
        ChatViewModelSlotManager(maxSlots = ChatViewModelSlotManager.DEFAULT_MAX_SLOTS)
    }

    single(createdAtStart = true) {
        SearchNavigationCoordinator(
            sessionSelectionController = get(),
            navigationState = get(),
        )
    }
}
