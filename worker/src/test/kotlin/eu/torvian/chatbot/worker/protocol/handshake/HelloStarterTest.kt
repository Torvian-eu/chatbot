package eu.torvian.chatbot.worker.protocol.handshake

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.protocol.ids.InteractionIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InMemoryInteractionRegistry
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [HelloStarter].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelloStarterTest {
    @Test
    fun `start returns launched interaction`() = runTest {
        val registry = InMemoryInteractionRegistry()
        val starter = HelloStarter(
            interactionScope = backgroundScope,
            registry = registry,
            interactionIdProvider = FixedInteractionIdProvider("int-1"),
            emitter = NoopEmitter(),
            handshakeContext = InMemorySessionHandshakeContext(),
            messageIdProvider = FixedMessageIdProvider()
        )

        val result = starter.start(
            workerUid = "worker-1",
            capabilities = listOf("mcp.tool.call"),
            supportedProtocolVersions = listOf(1)
        )

        assertTrue(result is HelloStartResult.Started)
        assertEquals("int-1", result.interactionId)
        assertTrue(registry.get("int-1") != null)
    }

    private class FixedInteractionIdProvider(
        private val interactionId: String
    ) : InteractionIdProvider {
        override fun nextInteractionId(): String = interactionId
    }

    private class FixedMessageIdProvider : MessageIdProvider {
        override fun nextMessageId(): String = "msg-1"
    }

    private class NoopEmitter : OutboundMessageEmitter {
        override suspend fun emit(message: WorkerProtocolMessage) = Unit
    }
}
