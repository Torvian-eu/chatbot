package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Request body for sending a new message to a chat session.
 *
 * @property content The user's message content.
 * @property parentMessageId The ID of the message this is a reply to (null for initial messages or if replying to the root of a new thread branch).
 * @property isStreaming Whether the assistant message should be streamed (true) or not (false).
 */
@Serializable
data class ProcessNewMessageRequest(
    val content: String,
    val parentMessageId: Long? = null,
    val isStreaming: Boolean = true
)