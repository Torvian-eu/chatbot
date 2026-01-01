package eu.torvian.chatbot.common.models.api.core

import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import kotlinx.serialization.Serializable

/**
 * Request body for inserting a new message into a chat session.
 *
 * @property sessionId The ID of the session to insert the message into.
 * @property targetMessageId The ID of the message to insert relative to. If null, inserts a new root message (position is ignored).
 * @property position The position to insert the new message relative to the target message. Ignored if targetMessageId is null.
 * @property role The role of the new message (user or assistant).
 * @property content The content of the new message.
 * @property modelId The ID of the model to use for generating the assistant response (optional).
 * @property settingsId The ID of the settings to use for generating the assistant response (optional).
 * @property fileReferences Optional list of file references attached to the message.
 */
@Serializable
data class InsertMessageRequest(
    val sessionId: Long,
    val targetMessageId: Long?,
    val position: MessageInsertPosition,
    val role: ChatMessage.Role,
    val content: String,
    val modelId: Long? = null,
    val settingsId: Long? = null,
    val fileReferences: List<FileReference> = emptyList()
)
