package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.core.FileReference
import kotlinx.serialization.Serializable

/**
 * Request body for updating the content and optionally file references of an existing message.
 *
 * @property content The new text content for the message.
 * @property fileReferences Optional new list of file references. If null, keeps existing.
 */
@Serializable
data class UpdateMessageRequest(
    val content: String,
    val fileReferences: List<FileReference>? = null
)