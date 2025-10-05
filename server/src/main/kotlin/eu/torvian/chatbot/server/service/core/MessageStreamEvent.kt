package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Internal streaming events emitted by MessageService during streaming message processing.
 * These events are service-layer specific and should be mapped to appropriate presentation layer events.
 */
sealed class MessageStreamEvent {
    /**
     * Emitted when the user's message has been processed and saved.
     *
     * @property message The fully saved user message.
     */
    data class UserMessageSaved(val message: ChatMessage.UserMessage) : MessageStreamEvent()

    /**
     * Emitted when the assistant's message starts, providing the initial assistant message with empty content.
     * The assistant message contains a temporary ID that will be replaced with the real ID at the end.
     *
     * @property assistantMessage The initial assistant message, including a temporary client-side ID.
     */
    data class AssistantMessageStarted(
        val assistantMessage: ChatMessage.AssistantMessage
    ) : MessageStreamEvent()

    /**
     * Emitted for each new content chunk from the LLM.
     *
     * @property messageId The temporary ID of the assistant message being updated.
     * @property deltaContent The new content chunk to append.
     */
    data class AssistantMessageDelta(val messageId: Long, val deltaContent: String) : MessageStreamEvent()

    /**
     * Emitted when the assistant's message generation is complete.
     * Provides the temporary ID that was used, the final persisted assistant message with its real ID,
     * and the updated user message (which now has the assistant message as a child).
     *
     * @property tempMessageId The temporary ID used during streaming.
     * @property finalAssistantMessage The final, persisted assistant message with its real ID.
     * @property finalUserMessage The updated user message with the assistant message as a child.
     */
    data class AssistantMessageCompleted(
        val tempMessageId: Long,
        val finalAssistantMessage: ChatMessage.AssistantMessage,
        val finalUserMessage: ChatMessage.UserMessage
    ) : MessageStreamEvent()

//    TODO: Add usage stats event
//    /**
//     * Emitted when usage statistics are available.
//     *
//     * @property promptTokens Number of tokens in the prompt/context
//     * @property completionTokens Number of tokens in the generated completion
//     * @property totalTokens Total tokens used (prompt + completion)
//     */
//    data class UsageStats(
//        val promptTokens: Int,
//        val completionTokens: Int,
//        val totalTokens: Int
//    ) : MessageStreamEvent()

    /**
     * Emitted as the final signal to indicate the end of the entire stream.
     */
    data object StreamCompleted : MessageStreamEvent()
}
