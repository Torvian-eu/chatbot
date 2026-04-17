package eu.torvian.chatbot.worker.protocol.routing

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import eu.torvian.chatbot.worker.protocol.registry.InMemoryWorkerActiveInteractionRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [WorkerCommandMessageHandler].
 */
class WorkerCommandMessageHandlerTest {
    /**
     * Verifies that `command.message` payloads are routed to the active interaction by interaction ID.
     */
    @Test
    fun `command message is routed to active interaction`() = kotlinx.coroutines.test.runTest {
        val registry = InMemoryWorkerActiveInteractionRegistry()
        val emitter = RecordingEmitter()
        val interaction = RecordingInteraction(interactionId = "int-1")
        registry.register(interaction)

        val handler = WorkerCommandMessageHandler(
            registry = registry,
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )

        val inboundMessage = WorkerProtocolMessage(
            id = "in-1",
            type = WorkerProtocolMessageTypes.COMMAND_MESSAGE,
            interactionId = "int-1",
            payload = buildJsonObject {
                put("messageKind", "proceed")
                put("data", JsonObject(emptyMap()))
            }
        )

        handler.handle(inboundMessage)

        assertEquals(1, interaction.receivedMessages.size)
        assertEquals(inboundMessage, interaction.receivedMessages.single())
        assertEquals(0, emitter.messages.size)
    }

    /**
     * Verifies that unknown interaction IDs are rejected for `command.message` routing.
     */
    @Test
    fun `unknown interaction id is rejected`() = kotlinx.coroutines.test.runTest {
        val emitter = RecordingEmitter()
        val handler = WorkerCommandMessageHandler(
            registry = InMemoryWorkerActiveInteractionRegistry(),
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )

        handler.handle(
            WorkerProtocolMessage(
                id = "in-2",
                type = WorkerProtocolMessageTypes.COMMAND_MESSAGE,
                interactionId = "int-missing",
                payload = buildJsonObject {
                    put("messageKind", "proceed")
                    put("data", JsonObject(emptyMap()))
                }
            )
        )

        assertEquals(1, emitter.messages.size)
        val rejected = decodeProtocolPayload<WorkerCommandRejectedPayload>(
            emitter.messages.single().payload!!,
            "WorkerCommandRejectedPayload"
        ).getOrElse { error("Expected rejection payload to decode: $it") }
        assertEquals(WorkerProtocolRejectionReasons.UNKNOWN_INTERACTION_ID, rejected.reasonCode)
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
     * Recording active interaction used to verify follow-up message delivery.
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



