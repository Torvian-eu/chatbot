package eu.torvian.chatbot.app.viewmodel.chat

import androidx.lifecycle.ViewModelStoreOwner

/**
 * Supplies the [ViewModelStoreOwner] whose [androidx.lifecycle.ViewModelStore] holds the session-scoped
 * [ChatViewModel]s the UI resolves.
 *
 * Navigation-compose scopes ViewModels per destination: the Chat destination's `NavBackStackEntry`
 * owner — not the platform-root owner — is the one `ChatScreen`'s `koinViewModel()` calls resolve
 * against. `ChatScreen` publishes that owner into this provider during composition, so the
 * spawned-turn coordinator can resolve the spawned session's [ChatViewModel] into the **same store**
 * the UI uses and drive the exact instance the user sees when switching to the spawned session.
 *
 * This deliberately avoids `Koin.declare` + `get<ViewModelStoreOwner>()`: an isolated Koin app does
 * not resolve declared root instances by plain type lookup, so depending on it would crash (or
 * silently bind to the wrong store) instead of guaranteeing same-instance semantics.
 */
interface ChatViewModelStoreOwnerProvider {

    /**
     * Returns the owner currently published by `ChatScreen`, or `null` when no Chat destination has
     * been composed yet (spawning is impossible in that state, so `null` is an unborn-error signal).
     */
    var owner: ViewModelStoreOwner?
}

/**
 * Default [ChatViewModelStoreOwnerProvider] whose [owner] is updated from composition.
 *
 * @see ChatViewModelStoreOwnerProvider
 */
class MutableChatViewModelStoreOwnerProvider : ChatViewModelStoreOwnerProvider {

    /**
     * The [ViewModelStoreOwner] of the currently composed Chat destination (set by `ChatScreen`).
     */
    override var owner: ViewModelStoreOwner? = null
}