package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.worker.mcp.McpToolCallExecutor
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.interaction.McpToolCallInteraction

/**
 * Interaction factory for `mcp.tool.call` command requests.
 *
 * @property toolCallExecutor Executor used to perform local MCP tool calls.
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class McpToolCallInteractionFactory(
    private val toolCallExecutor: McpToolCallExecutor,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) : InteractionFactory {

    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: OutboundMessageEmitter
    ): Interaction {
        return McpToolCallInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            toolCallExecutor = toolCallExecutor,
            emitter = emitter,
            messageIdProvider = messageIdProvider
        )
    }
}
