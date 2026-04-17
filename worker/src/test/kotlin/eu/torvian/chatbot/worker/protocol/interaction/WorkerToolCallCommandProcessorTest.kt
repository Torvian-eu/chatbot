package eu.torvian.chatbot.worker.protocol.interaction

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRejectedPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolMessageTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolRejectionReasons
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [WorkerToolCallInteraction].
 */
class WorkerToolCallCommandProcessorTest {
    /**
     * Verifies that direct `tool.call` requests are rejected until implementation exists.
     */
    @Test
    fun `direct tool call is rejected as not implemented`() = kotlinx.coroutines.test.runTest {
        val emitter = RecordingEmitter()
        val interaction = WorkerToolCallInteraction(
            envelope = WorkerProtocolMessage(
                id = "in-1",
                type = WorkerProtocolMessageTypes.COMMAND_REQUEST,
                interactionId = "int-1",
                payload = null
            ),
            requestPayload = WorkerCommandRequestPayload(
                commandType = WorkerProtocolCommandTypes.TOOL_CALL,
                data = buildJsonObject {
                    put("toolName", "ping")
                }
            ),
            emitter = emitter,
            messageIdProvider = SequenceMessageIdProvider()
        )

        interaction.start()

        val outbound = emitter.messages

        assertEquals(1, outbound.size)
        assertEquals(WorkerProtocolMessageTypes.COMMAND_REJECTED, outbound.single().type)

        val rejected = decodeProtocolPayload<WorkerCommandRejectedPayload>(
            outbound.single().payload!!,
            "WorkerCommandRejectedPayload"
        ).getOrElse { error("Expected rejection payload to decode: $it") }
        assertEquals(WorkerProtocolCommandTypes.TOOL_CALL, rejected.commandType)
        assertEquals(WorkerProtocolRejectionReasons.NOT_IMPLEMENTED, rejected.reasonCode)
        assertEquals("Direct tool.call is not implemented yet", rejected.message)
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

