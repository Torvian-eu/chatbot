package eu.torvian.chatbot.app.viewmodel.chat

/**
 * Resolves the session-scoped [ChatViewModel] for a spawned session from regular (non-composable)
 * code.
 *
 * Chat ViewModels are registered with Koin's `viewModel` DSL, whose instances are scoped to a
 * [androidx.lifecycle.ViewModelStoreOwner] rather than being plain Koin services. This indirection
 * lets the spawned-turn coordinator obtain the **same** [ChatViewModel] instance the UI resolves for
 * a given session, so driving it through [ChatViewModel.loadSession] /
 * [ChatViewModel.sendMessage] populates the session's ChatState, approval pipeline, and turn state
 * exactly as a user-driven session would.
 *
 * Implementations are expected to be pure resolvers with no other side effects, and to be mockable
 * in tests (executor tests never touch Koin or ViewModelStores).
 */
interface SpawnedChatViewModelResolver {

    /**
     * Resolves (creating if needed) the [ChatViewModel] for [sessionId] in the app's ViewModelStore.
     *
     * @param sessionId The spawned session whose [ChatViewModel] is needed.
     * @return The session-scoped [ChatViewModel] (the same instance the UI resolves for that session).
     */
    suspend fun forSession(sessionId: Long): ChatViewModel
}