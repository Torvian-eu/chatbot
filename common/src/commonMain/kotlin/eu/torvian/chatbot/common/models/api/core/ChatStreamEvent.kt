package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.core.ChatMessage
import kotlinx.serialization.Serializable

/**
 * Represents a discrete, server-sent event delivered as part of a streaming chat completion.
 * These events are designed for incremental server-to-client communication, allowing clients
 * to build the chat message dynamically as it's generated.
 *
 * Each update carries an [eventType] corresponding to the 'event' field in Server-Sent Events (SSE).
 */
@Serializable
sealed interface ChatStreamEvent {
    val eventType: String

    /**
     * Sent once the user's message has been successfully processed and saved on the server.
     * This typically signals the start of the assistant's response generation on the client side.
     *
     * @property message The fully saved user message.
     */
    @Serializable
    data class UserMessageSaved(val message: ChatMessage.UserMessage) : ChatStreamEvent {
        override val eventType: String = "user_message"
    }

    /**
     * Sent when the assistant's message generation begins.
     * Provides an initial `ChatMessage.AssistantMessage` object, typically with empty content,
     * but containing a temporary ID that the client should use until the final message is sent.
     *
     * @property assistantMessage The initial assistant message, including a temporary client-side ID.
     */
    @Serializable
    data class AssistantMessageStart(
        val assistantMessage: ChatMessage.AssistantMessage
    ) : ChatStreamEvent {
        override val eventType: String = "assistant_message_start"
    }

    /**
     * Sent for each new content chunk received from the Large Language Model (LLM).
     * Clients should append `deltaContent` to the existing message identified by `messageId`.
     * The `messageId` here refers to the client-side temporary ID provided in `AssistantMessageStart`.
     *
     * @property messageId The temporary ID of the assistant message being updated.
     * @property deltaContent The new content chunk to append.
     */
    @Serializable
    data class AssistantMessageDelta(val messageId: Long, val deltaContent: String) : ChatStreamEvent {
        override val eventType: String = "assistant_message_delta"
    }

    /**
     * Sent when the assistant's message generation is complete and the message has been persisted.
     * This signals the end of a specific assistant message's stream.
     *
     * @property tempMessageId The temporary ID used during streaming for this assistant message.
     * @property finalAssistantMessage The final, persisted assistant message with its real, permanent ID and full content.
     * @property finalUserMessage The updated user message, which now includes the generated assistant message as a child.
     */
    @Serializable
    data class AssistantMessageEnd(
        val tempMessageId: Long,
        val finalAssistantMessage: ChatMessage.AssistantMessage,
        val finalUserMessage: ChatMessage.UserMessage
    ) : ChatStreamEvent {
        override val eventType: String = "assistant_message_end"
    }

    /**
     * Sent as the final signal to indicate the end of the entire chat completion stream.
     * After this event, no further events for this specific completion will be sent on this stream.
     */
    @Serializable
    data object StreamCompleted : ChatStreamEvent {
        override val eventType: String = "done"
    }

    /**
     * Sent if a non-fatal error occurs during the streaming process that doesn't terminate the connection.
     * The client should display this error appropriately, but the stream may continue if applicable.
     *
     * @property error Details of the API error that occurred.
     */
    @Serializable
    data class ErrorOccurred(val error: ApiError) : ChatStreamEvent {
        override val eventType: String = "error"
    }
}