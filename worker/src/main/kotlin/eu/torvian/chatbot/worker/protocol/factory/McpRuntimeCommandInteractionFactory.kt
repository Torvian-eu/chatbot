package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.worker.mcp.McpRuntimeCommandExecutor
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.interaction.McpRuntimeCommandInteraction
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter

/**
 * Interaction factory for worker local MCP runtime command requests.
 *
 * @property executor Command executor used by created interactions.
 * @property messageIdProvider Message-ID provider used by created interactions.
 */
class McpRuntimeCommandInteractionFactory(
    private val executor: McpRuntimeCommandExecutor,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider()
) : InteractionFactory {

    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: OutboundMessageEmitter
    ): Interaction {
        return McpRuntimeCommandInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            executor = executor,
            emitter = emitter,
            messageIdProvider = messageIdProvider
        )
    }
}
