package eu.torvian.chatbot.worker.protocol.handshake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [InMemorySessionHandshakeContext].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHandshakeContextTest {
    @Test
    fun `reset clears current and terminal state`() = runTest {
        val context = InMemorySessionHandshakeContext()
        context.markPending()
        context.markSucceeded(
            SessionWelcomeState(
                workerUid = "worker-1",
                selectedProtocolVersion = 1,
                acceptedCapabilities = listOf("mcp.tool.call")
            )
        )

        context.reset()

        assertNull(context.get())
        val waiter = launch { context.awaitTerminalState() }
        assertTrue(!waiter.isCompleted)
        waiter.cancelAndJoin()
    }

    @Test
    fun `await terminal state returns succeeded state`() = runTest {
        val context = InMemorySessionHandshakeContext()
        val waiter = launch { context.awaitTerminalState() }

        context.markSucceeded(
            SessionWelcomeState(
                workerUid = "worker-1",
                selectedProtocolVersion = 1,
                acceptedCapabilities = emptyList()
            )
        )

        waiter.join()
        assertTrue(context.get() is SessionHandshakeState.Succeeded)
    }
}
