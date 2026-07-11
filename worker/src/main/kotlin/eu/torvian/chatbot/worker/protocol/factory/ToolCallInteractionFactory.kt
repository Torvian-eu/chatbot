package eu.torvian.chatbot.worker.protocol.factory

import eu.torvian.chatbot.common.models.api.worker.protocol.core.WorkerProtocolMessage
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCommandRequestPayload
import eu.torvian.chatbot.worker.builtin.BuiltInToolAuthorizationValidator
import eu.torvian.chatbot.worker.builtin.BuiltInToolCallExecutor
import eu.torvian.chatbot.worker.protocol.ids.MessageIdProvider
import eu.torvian.chatbot.worker.protocol.ids.UuidMessageIdProvider
import eu.torvian.chatbot.worker.protocol.interaction.Interaction
import eu.torvian.chatbot.worker.protocol.interaction.ToolCallInteraction
import eu.torvian.chatbot.worker.protocol.transport.OutboundMessageEmitter

class ToolCallInteractionFactory(
    private val authorizationValidator: BuiltInToolAuthorizationValidator,
    private val toolCallExecutor: BuiltInToolCallExecutor,
    private val messageIdProvider: MessageIdProvider = UuidMessageIdProvider(),
) : InteractionFactory {
    override fun create(
        envelope: WorkerProtocolMessage,
        requestPayload: WorkerCommandRequestPayload,
        emitter: OutboundMessageEmitter,
    ): Interaction {
        return ToolCallInteraction(
            envelope = envelope,
            requestPayload = requestPayload,
            authorizationValidator = authorizationValidator,
            toolCallExecutor = toolCallExecutor,
            emitter = emitter,
            messageIdProvider = messageIdProvider,
        )
    }
}
