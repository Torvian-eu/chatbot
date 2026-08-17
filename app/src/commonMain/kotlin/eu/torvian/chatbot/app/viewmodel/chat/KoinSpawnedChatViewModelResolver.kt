package eu.torvian.chatbot.app.viewmodel.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.scope.Scope
import org.koin.viewmodel.defaultExtras
import org.koin.viewmodel.resolveViewModel

/**
 * Koin-backed [SpawnedChatViewModelResolver] that replicates the exact resolution `koinViewModel(key = ...)`
 * performs in composition.
 *
 * This adapter is the only file in the codebase that depends on Koin's `@KoinInternalApi`
 * [resolveViewModel] (the non-composable call the Compose `koinViewModel` delegates to); keeping that
 * dependency quarantined here means a Koin upgrade changes exactly one file. The resolver must be
 * given the [Scope] the `viewModel` definitions were installed into (the app's root scope).
 *
 * The [androidx.lifecycle.ViewModelStoreOwner] is **not** captured at construction: navigation-compose
 * scopes ViewModels to the Chat destination's `NavBackStackEntry`-provided owner, so the UI's
 * session-scoped [ChatViewModel]s live in the store of the owner `ChatScreen` resolves from.
 * `ChatScreen` publishes that owner into [ChatViewModelStoreOwnerProvider] during composition, and the
 * resolver reads it at spawn time so it drives the exact instance the UI shows for the spawned
 * session. Capturing the owner at construction would bind the resolver to whatever owner existed
 * first (the platform root owner) — a different store — and produce a parallel, unloaded instance
 * that gets reloaded (`clearSession`) when the user switches to the spawned session, breaking manual
 * tool approvals.
 *
 * @property slotManager Maps session ids to the stable LRU slot keys `ChatScreen` uses for its
 *            [ChatViewModel] lookups, so the resolved instance matches the UI's.
 * @property scope The Koin scope containing the `viewModel` definitions (the app's root scope).
 * @property ownerProvider Source of the current Chat-destination [androidx.lifecycle.ViewModelStoreOwner]
 *            (set by `ChatScreen`), resolved at spawn time.
 */
class KoinSpawnedChatViewModelResolver(
    private val slotManager: ChatViewModelSlotManager,
    private val scope: Scope,
    private val ownerProvider: ChatViewModelStoreOwnerProvider,
) : SpawnedChatViewModelResolver {

    /**
     * Resolves (creating if needed) the [ChatViewModel] for [sessionId] in the store of the owner
     * currently published by [ChatViewModelStoreOwnerProvider] (the Chat destination's owner).
     *
     * Resolution is hopped to [Dispatchers.Main] because [androidx.lifecycle.ViewModelStore] is a
     * plain key-to-ViewModel map that is not thread-safe and AndroidX `ViewModelProvider` is
     * main-thread oriented; the caller may run on a background context.
     *
     * @param sessionId The spawned session whose [ChatViewModel] is needed.
     * @return The session-scoped [ChatViewModel] (same instance as the UI resolves for that session).
     * @throws IllegalStateException When no Chat destination has published its owner yet (the
     *            spawned coordinator cannot run before a Chat screen exists).
     */
    @OptIn(KoinInternalApi::class)
    override suspend fun forSession(sessionId: Long): ChatViewModel = withContext(Dispatchers.Main) {
        // The owner is read at spawn time (not construction): `ChatScreen` may compose after this
        // single is built, and the executor must resolve into the same ViewModelStore the UI uses.
        val viewModelStoreOwner = ownerProvider.owner
            ?: error("Chat destination ViewModelStoreOwner is not published; ChatScreen must be composed before spawning")
        resolveViewModel(
            vmClass = ChatViewModel::class,
            viewModelStore = viewModelStoreOwner.viewModelStore,
            key = slotManager.resolveViewModelKey(sessionId),
            extras = defaultExtras(viewModelStoreOwner),
            qualifier = null,
            scope = scope,
        )
    }
}