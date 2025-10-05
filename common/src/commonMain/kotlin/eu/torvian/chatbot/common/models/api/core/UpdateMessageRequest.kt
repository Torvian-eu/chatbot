package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Request body for updating the content of an existing message.
 *
 * @property content The new text content for the message.
 */
@Serializable
data class UpdateMessageRequest(
    val content: String
)