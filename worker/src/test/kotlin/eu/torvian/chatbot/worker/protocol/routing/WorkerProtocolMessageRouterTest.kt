package eu.torvian.chatbot.worker.protocol.routing

import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.protocol.handshake.InMemorySessionHandshakeContext
import eu.torvian.chatbot.worker.protocol.handshake.SessionWelcomeState
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.registry.InMemoryInteractionRegistry
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [WorkerProtocolMessageRouter].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerProtocolMessageRouterTest {
    @Test
    fun `active interaction routing is prioritized over type routing`() = runTest {
        val registry = InMemoryInteractionRegistry()
        val handshakeContext = InMemorySessionHandshakeContext()
        val emitter = RecordingEmitter()
        val interaction = RecordingInteraction(interactionId = "int-1")
        registry.register(interaction)

        val commandRequestProcessor = RecordingIncomingMessageProcessor()
        val router = WorkerProtocolMessageRouter(
            registry = registry,
            handshakeContext = handshakeContext,
            commandRequestProcessor = commandRequestProcessor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )

        val welcomeEnvelope = WorkerProtocolMessage(
            id = "in-1",
            type = WorkerProtocolMessageTypes.SESSION_WELCOME,
            interactionId = "int-1"
        )
        router.process(welcomeEnvelope)

        assertEquals(listOf(welcomeEnvelope), interaction.receivedMessages)
        assertEquals(emptyList(), commandRequestProcessor.messages)
        assertEquals(emptyList(), emitter.messages)
    }

    @Test
    fun `command request is rejected before handshake success`() = runTest {
        val router = createRouter()

        router.router.process(commandRequestEnvelope())

        assertRejected(router.emitter.messages.single(), WorkerProtocolRejectionReasons.HANDSHAKE_NOT_COMPLETE)
        assertTrue(router.commandRequestProcessor.messages.isEmpty())
    }

    @Test
    fun `command message is rejected before handshake success`() = runTest {
        val router = createRouter()

        router.router.process(commandMessageEnvelope())

        assertRejected(router.emitter.messages.single(), WorkerProtocolRejectionReasons.HANDSHAKE_NOT_COMPLETE)
    }

    @Test
    fun `command message is rejected after handshake success without active interaction`() = runTest {
        val router = createRouter(handshakeSucceeded = true)

        router.router.process(commandMessageEnvelope())

        assertRejected(router.emitter.messages.single(), WorkerProtocolRejectionReasons.UNKNOWN_INTERACTION_ID)
    }

    @Test
    fun `command request is forwarded after handshake success`() = runTest {
        val router = createRouter(handshakeSucceeded = true)

        val envelope = commandRequestEnvelope()
        router.router.process(envelope)

        assertEquals(listOf(envelope), router.commandRequestProcessor.messages)
        assertTrue(router.emitter.messages.isEmpty())
    }

    @Test
    fun `session welcome still reaches active hello interaction`() = runTest {
        val registry = InMemoryInteractionRegistry()
        val handshakeContext = InMemorySessionHandshakeContext()
        val emitter = RecordingEmitter()
        val interaction = RecordingInteraction(interactionId = "hello-1")
        registry.register(interaction)

        val router = WorkerProtocolMessageRouter(
            registry = registry,
            handshakeContext = handshakeContext,
            commandRequestProcessor = RecordingIncomingMessageProcessor(),
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )

        val welcomeEnvelope = WorkerProtocolMessage(
            id = "in-4",
            type = WorkerProtocolMessageTypes.SESSION_WELCOME,
            interactionId = "hello-1"
        )
        router.process(welcomeEnvelope)

        assertEquals(listOf(welcomeEnvelope), interaction.receivedMessages)
        assertTrue(emitter.messages.isEmpty())
    }

    @Test
    fun `unsupported message behavior still works for non command traffic`() = runTest {
        val router = createRouter(handshakeSucceeded = true)

        val envelope = WorkerProtocolMessage(
            id = "in-5",
            type = "unknown.type",
            interactionId = "int-1"
        )
        router.router.process(envelope)

        assertRejected(router.emitter.messages.single(), WorkerProtocolRejectionReasons.UNSUPPORTED_MESSAGE_TYPE)
    }

    private fun createRouter(handshakeSucceeded: Boolean = false): TestRouter {
        val registry = InMemoryInteractionRegistry()
        val handshakeContext = InMemorySessionHandshakeContext()
        if (handshakeSucceeded) {
            handshakeContext.markSucceeded(
                SessionWelcomeState(
                    workerUid = "worker-1",
                    selectedProtocolVersion = 1,
                    acceptedCapabilities = emptyList()
                )
            )
        }
        val emitter = RecordingEmitter()
        val commandRequestProcessor = RecordingIncomingMessageProcessor()
        val router = WorkerProtocolMessageRouter(
            registry = registry,
            handshakeContext = handshakeContext,
            commandRequestProcessor = commandRequestProcessor,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )
        return TestRouter(router, commandRequestProcessor, emitter)
    }

    private fun commandRequestEnvelope() = WorkerProtocolMessage(
        id = "in-2",
        type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
        interactionId = "int-missing"
    )

    private fun commandMessageEnvelope() = WorkerProtocolMessage(
        id = "in-3",
        type = WorkerProtocolMessageTypes.COMMAND_MESSAGE,
        interactionId = "int-missing"
    )

    private fun assertRejected(message: WorkerProtocolMessage, reasonCode: String) {
        assertEquals(WorkerProtocolMessageTypes.COMMAND_REJECTED, message.type)
        assertTrue(message.payload == null || message.payload.toString().contains(reasonCode))
    }

    private data class TestRouter(
        val router: WorkerProtocolMessageRouter,
        val commandRequestProcessor: RecordingIncomingMessageProcessor,
        val emitter: RecordingEmitter
    )

    private class RecordingEmitter : OutboundMessageEmitter {
        val messages: MutableList<WorkerProtocolMessage> = mutableListOf()

        override suspend fun emit(message: WorkerProtocolMessage) {
            messages += message
        }
    }

    private class RecordingInteraction(
        override val interactionId: String
    ) : Interaction {
        val receivedMessages: MutableList<WorkerProtocolMessage> = mutableListOf()

        override suspend fun start() = Unit

        override suspend fun onMessage(message: WorkerProtocolMessage) {
            receivedMessages += message
        }
    }

    private class RecordingIncomingMessageProcessor : IncomingMessageProcessor {
        val messages: MutableList<WorkerProtocolMessage> = mutableListOf()

        override suspend fun process(message: WorkerProtocolMessage) {
            messages += message
        }
    }

    private class SequenceMessageIdProvider : MessageIdProvider {
        private var counter: Int = 0

        override fun nextMessageId(): String {
            counter += 1
            return "msg-$counter"
        }
    }
}
