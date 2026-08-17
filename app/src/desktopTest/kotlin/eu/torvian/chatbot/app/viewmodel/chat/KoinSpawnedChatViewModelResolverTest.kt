package eu.torvian.chatbot.app.viewmodel.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Tests for [KoinSpawnedChatViewModelResolver].
 *
 * The resolver is the bridge between the spawn executor and the UI: it must always resolve a session's
 * [ChatViewModel] into the same [ViewModelStore] the UI's `ChatScreen` uses. `ChatScreen` publishes its
 * destination owner (a `NavHost` back-stack entry) into [ChatViewModelStoreOwnerProvider] during
 * composition, and the resolver reads that owner **at spawn time** — a resolver that captured the
 * owner when it was constructed would stay bound to the startup root owner and create a parallel,
 * unloaded instance for the spawned session (reloaded on session switch, breaking manual approvals).
 */
class KoinSpawnedChatViewModelResolverTest {

    /** Minimal owner with its own [ViewModelStore], mirroring how a NavHost destination scopes VMs. */
    private class FakeViewModelStoreOwner : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = ViewModelStore()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `forSession resolves against the owner currently published by ChatScreen`() = runBlocking {
        val app = koinApplication {
            modules(
                module {
                    // A mock suffices: the test asserts store/key identity, not ChatViewModel behavior.
                    viewModel { mockk<ChatViewModel>() }
                    single<ChatViewModelSlotManager> { ChatViewModelSlotManager(maxSlots = 4) }
                }
            )
        }
        val rootScope = app.koin.scopeRegistry.rootScope
        val slotManager = app.koin.get<ChatViewModelSlotManager>()

        val ownerProvider = MutableChatViewModelStoreOwnerProvider()
        val resolver = KoinSpawnedChatViewModelResolver(
            slotManager = slotManager,
            scope = rootScope,
            ownerProvider = ownerProvider
        )

        // The resolver is built once (a Koin single) while only the startup fallback owner exists.
        val startupOwner = FakeViewModelStoreOwner()
        ownerProvider.owner = startupOwner
        val viaStartupOwner = resolver.forSession(1L)

        // ChatScreen later publishes its destination owner (the bug scenario: a NavHost entry whose
        // store differs from the root owner's). The same resolver instance must now honor the new
        // owner instead of the owner that existed when it was constructed.
        val chatDestinationOwner = FakeViewModelStoreOwner()
        ownerProvider.owner = chatDestinationOwner
        val viaDestinationOwner = resolver.forSession(1L)

        assertNotSame(viaStartupOwner, viaDestinationOwner)

        // Re-resolution is stable for the current owner: the UI switching to the spawned session
        // later gets precisely the instance the resolver drives.
        assertSame(viaDestinationOwner, resolver.forSession(1L))
    }
}