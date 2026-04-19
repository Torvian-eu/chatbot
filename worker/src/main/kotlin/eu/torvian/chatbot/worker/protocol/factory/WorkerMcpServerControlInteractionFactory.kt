package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.worker.mcp.WorkerMcpServerControlCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import eu.torvian.chatbot.worker.protocol.interaction.WorkerMcpServerControlInteraction
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter

/**
 * Interaction factory for MCP server runtime-control command requests.
 *
 * @property executor Command executor used by created interactions.
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class WorkerMcpServerControlInteractionFactory(
    private val executor: WorkerMcpServerControlCommandExecutor,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : WorkerInteractionFactory {
    /**
     * @param envelope Original inbound `command.request` envelope.
     * @param requestPayload Decoded request payload.
     * @param emitter Outbound protocol emitter used by the created interaction.
     * @return New MCP server-control interaction.
     */
    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: WorkerOutboundMessageEmitter
    ): WorkerActiveInteraction {
        return WorkerMcpServerControlInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            executor = executor,
            emitter = emitter,
            messageIdProvider = messageIdProvider
        )
    }
}

