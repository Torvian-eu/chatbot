package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.mcp.WorkerToolCallExecutor
import eu.torvian.chatbot.worker.protocol.transport.WorkerOutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidWorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.WorkerMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.WorkerActiveInteraction
import eu.torvian.chatbot.worker.protocol.interaction.WorkerMcpToolCallInteraction

/**
 * Interaction factory for `mcp.tool.call` command requests.
 *
 * @property toolCallExecutor Executor used to perform local MCP tool calls.
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class WorkerMcpToolCallInteractionFactory(
    private val toolCallExecutor: WorkerToolCallExecutor,
    private val messageIdProvider: WorkerMessageIdProvider = UuidWorkerMessageIdProvider()
) : WorkerInteractionFactory {
    /**
     * @param envelope Original inbound `command.request` envelope.
     * @param requestPayload Decoded request payload.
     * @param emitter Outbound protocol emitter used by the created interaction.
     * @return New MCP tool-call interaction.
     */
    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: WorkerOutboundMessageEmitter
    ): WorkerActiveInteraction {
        return WorkerMcpToolCallInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            toolCallExecutor = toolCallExecutor,
            emitter = emitter,
            messageIdProvider = messageIdProvider
        )
    }
}
