package eu.torvian.chatbot.worker.protocol.routing

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import eu.torvian.chatbot.worker.protocol.registry.InMemoryWorkerActiveInteractionRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [WorkerProtocolMessageRouter].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerProtocolMessageRouterTest {
    /**
     * Verifies that active interactions receive envelopes by interaction ID before type-based routing executes.
     */
    @Test
    fun `active interaction routing is prioritized over type routing`() = runTest {
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val emitter = RecordingEmitter()
        val interaction = RecordingInteraction(interactionId = "int-1")
        registry.register(interaction)

        val commandRequestProcessor = RecordingIncomingMessageProcessor()
        val commandMessageHandler = WorkerCommandMessageHandler(
            registry = registry,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )
        val router = WorkerProtocolMessageRouter(
            registry = registry,
            commandRequestProcessor = commandRequestProcessor,
            commandMessageHandler = commandMessageHandler,
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

    /**
     * Verifies that command requests still use type-based routing when no active interaction matches.
     */
    @Test
    fun `command request falls back to type routing when interaction is not active`() = runTest {
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val emitter = RecordingEmitter()
        val commandRequestProcessor = RecordingIncomingMessageProcessor()
        val commandMessageHandler = WorkerCommandMessageHandler(
            registry = registry,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )
        val router = WorkerProtocolMessageRouter(
            registry = registry,
            commandRequestProcessor = commandRequestProcessor,
            commandMessageHandler = commandMessageHandler,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )

        val requestEnvelope = WorkerProtocolMessage(
            id = "in-2",
            type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
            interactionId = "int-missing"
        )
        router.process(requestEnvelope)

        assertEquals(listOf(requestEnvelope), commandRequestProcessor.messages)
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
     * Recording interaction used to verify registry-based delivery.
     *
     * @property interactionId Stable interaction identifier used by the registry.
     */
    private class RecordingInteraction(
        override val interactionId: String
    ) : WorkerActiveInteraction {
        /**
         * Messages delivered through [onMessage].
         */
        val receivedMessages: MutableList<WorkerProtocolMessage> = mutableListOf()

        /**
         * No-op for this test helper.
         */
        override suspend fun start() = Unit

        /**
         * Records protocol envelopes for assertions.
         *
         * @param message Inbound protocol envelope addressed to this interaction.
         */
        override suspend fun onMessage(message: WorkerProtocolMessage) {
            receivedMessages += message
        }
    }

    /**
     * Recording processor used to capture type-routed envelopes.
     */
    private class RecordingIncomingMessageProcessor : WorkerIncomingMessageProcessor {
        /**
         * Messages processed by this helper.
         */
        val messages: MutableList<WorkerProtocolMessage> = mutableListOf()

        /**
         * @param message Inbound protocol envelope to record.
         */
        override suspend fun process(message: WorkerProtocolMessage) {
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

