package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for sending a new message to a chat session.
 *
 * @property content The user's message content.
 * @property parentMessageId The ID of the message this is a reply to (null for initial messages or if replying to the root of a new thread branch).
 */
@Serializable
data class ProcessNewMessageRequest(
    val content: String,
    val parentMessageId: Long? = null
)