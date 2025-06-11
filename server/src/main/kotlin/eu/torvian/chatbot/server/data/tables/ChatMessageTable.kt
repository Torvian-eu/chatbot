package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatMessage.Role
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Exposed table definition for chat messages.
 * Corresponds to the [ChatMessage] DTO.
 *
 * @property sessionId Reference to the parent chat session
 * @property role The role of the message sender (user or assistant)
 * @property content The text content of the message
 * @property createdAt Timestamp when the message was created
 * @property updatedAt Timestamp when the message was last updated
 * @property parentMessageId Reference to the parent message in a thread (null for root messages)
 * @property childrenMessageIds JSON array of child message IDs for threading
 */
object ChatMessageTable : LongIdTable("chat_messages") {
    val sessionId = reference(
        "session_id",
        ChatSessionTable,
        onDelete = ReferenceOption.CASCADE
    )
    val role = enumerationByName<Role>("role", 50)
    val content = text("content")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val parentMessageId = reference(
        "parent_message_id",
        ChatMessageTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
    val childrenMessageIds = text("children_message_ids")

    // Add indices for sessionId and parentMessageId for efficient querying
    init {
        index(false, sessionId)
        index(false, parentMessageId)
    }
}
