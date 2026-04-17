package eu.torvian.chatbot.worker.protocol.handshake

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionHelloPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerSessionWelcomePayload
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.builders.sessionWelcome
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.registry.InMemoryWorkerActiveInteractionRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Unit tests for [WorkerSessionHelloInteraction].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerSessionHelloInteractionTest {
    /**
     * Verifies that starting the interaction emits one `session.hello` envelope.
     */
    @Test
    fun `start emits session hello envelope`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val handshakeStateStore = InMemoryWorkerSessionHandshakeStateStore()
        val interaction = WorkerSessionHelloInteraction(
            interactionId = "int-1",
            workerUid = "worker-1",
            capabilities = listOf("mcp.tool.call"),
            supportedProtocolVersions = listOf(1),
            workerVersion = "1.0.0",
            emitter = emitter,
            registry = registry,
            handshakeStateStore = handshakeStateStore,
            messageIdProvider = SequenceMessageIdProvider()
        )
        registry.register(interaction)

        val job = launch { interaction.start() }
        runCurrent()

        val helloEnvelope = emitter.messages.single()
        assertEquals("msg-1", helloEnvelope.id)
        assertEquals("session.hello", helloEnvelope.type)
        assertEquals("int-1", helloEnvelope.interactionId)
        val helloPayload = decodeProtocolPayload<WorkerSessionHelloPayload>(
            payload = helloEnvelope.payload!!,
            targetType = "WorkerSessionHelloPayload"
        ).fold(
            ifLeft = { error -> error("Expected session hello payload to decode: $error") },
            ifRight = { decoded -> decoded }
        )
        assertEquals("worker-1", helloPayload.workerUid)
        assertEquals(listOf("mcp.tool.call"), helloPayload.capabilities)
        assertEquals(listOf(1), helloPayload.supportedProtocolVersions)
        assertEquals("1.0.0", helloPayload.workerVersion)
        assertEquals(WorkerSessionHandshakeState.Pending, handshakeStateStore.get("int-1"))

        job.cancelAndJoin()
    }

    /**
     * Verifies that a valid welcome message completes the interaction and records success state.
     */
    @Test
    fun `valid session welcome completes handshake`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val handshakeStateStore = InMemoryWorkerSessionHandshakeStateStore()
        val interaction = WorkerSessionHelloInteraction(
            interactionId = "int-1",
            workerUid = "worker-1",
            capabilities = listOf("mcp.tool.call"),
            supportedProtocolVersions = listOf(1),
            workerVersion = null,
            emitter = emitter,
            registry = registry,
            handshakeStateStore = handshakeStateStore,
            messageIdProvider = SequenceMessageIdProvider()
        )
        registry.register(interaction)

        val job = launch { interaction.start() }
        runCurrent()

        val helloEnvelope = emitter.messages.single()
        interaction.onMessage(
            sessionWelcome(
                id = "server-1",
                replyTo = helloEnvelope.id,
                interactionId = "int-1",
                payload = WorkerSessionWelcomePayload(
                    workerUid = "worker-1",
                    selectedProtocolVersion = 1,
                    acceptedCapabilities = listOf("mcp.tool.call")
                )
            )
        )
        runCurrent()

        val successState = handshakeStateStore.get("int-1")
        assertIs<WorkerSessionHandshakeState.Succeeded>(successState)
        assertEquals("worker-1", successState.welcome.workerUid)
        assertEquals(1, successState.welcome.selectedProtocolVersion)
        assertEquals(listOf("mcp.tool.call"), successState.welcome.acceptedCapabilities)
        assertEquals(null, registry.get("int-1"))
        assertEquals(true, job.isCompleted)
    }

    /**
     * Verifies that malformed welcome messages fail the interaction without crashing.
     */
    @Test
    fun `malformed session welcome fails interaction cleanly`() = runTest {
        val emitter = RecordingEmitter()
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val handshakeStateStore = InMemoryWorkerSessionHandshakeStateStore()
        val interaction = WorkerSessionHelloInteraction(
            interactionId = "int-1",
            workerUid = "worker-1",
            capabilities = listOf("mcp.tool.call"),
            supportedProtocolVersions = listOf(1),
            workerVersion = null,
            emitter = emitter,
            registry = registry,
            handshakeStateStore = handshakeStateStore,
            messageIdProvider = SequenceMessageIdProvider()
        )
        registry.register(interaction)

        val job = launch { interaction.start() }
        runCurrent()

        interaction.onMessage(
            WorkerProtocolMessage(
                id = "server-1",
                type = "session.welcome",
                replyTo = "msg-1",
                interactionId = "int-1",
                payload = null
            )
        )
        runCurrent()

        val failureState = handshakeStateStore.get("int-1")
        assertIs<WorkerSessionHandshakeState.Failed>(failureState)
        assertEquals("session.welcome payload is missing", failureState.reason)
        assertEquals(null, registry.get("int-1"))
        assertEquals(true, job.isCompleted)
    }

    /**
     * Recording outbound emitter used for assertions.
     */
    private class RecordingEmitter : WorkerOutboundMessageEmitter {
        /**
         * Collected outbound messages in send order.
         */
        val messages: MutableList<WorkerProtocolMessage> = mutableListOf()

        /**
         * @param message Outbound protocol envelope to record.
         */
        override suspend fun emit(message: WorkerProtocolMessage) {
            messages += message
        }
    }

    /**
     * Deterministic message-id provider for stable protocol assertions.
     */
    private class SequenceMessageIdProvider : WorkerMessageIdProvider {
        /**
         * Internal counter used to produce stable increasing IDs.
         */
        private var counter: Int = 0

        /**
         * Produces the next deterministic test message ID.
         *
         * @return Stable identifier in the form `msg-N`.
         */
        override fun nextMessageId(): String {
            counter += 1
            return "msg-$counter"
        }
    }
}

