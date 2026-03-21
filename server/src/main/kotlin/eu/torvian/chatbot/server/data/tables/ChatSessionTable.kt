package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.tables.ChatSessionTable.createdAt
import eu.torvian.chatbot.server.data.tables.ChatSessionTable.currentModelId
import eu.torvian.chatbot.server.data.tables.ChatSessionTable.currentSettingsId
import eu.torvian.chatbot.server.data.tables.ChatSessionTable.groupId
import eu.torvian.chatbot.server.data.tables.ChatSessionTable.name
import eu.torvian.chatbot.server.data.tables.ChatSessionTable.updatedAt
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for chat sessions.
 * Corresponds to the [ChatSessionEntity].
 *
 * @property name The name of the chat session
 * @property createdAt Timestamp when the session was created
 * @property updatedAt Timestamp when the session was last updated
 * @property groupId Reference to the chat group this session belongs to
 * @property currentModelId Reference to the currently selected LLM model for this session
 * @property currentSettingsId Reference to the currently selected model settings for this session
 */
object ChatSessionTable : LongIdTable("chat_sessions") {
    val name = varchar("name", 255)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val groupId = reference("group_id", ChatGroupTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val currentModelId = reference("current_model_id", LLMModelTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val currentSettingsId =
        reference("current_settings_id", ModelSettingsTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // Add index for groupId to speed up grouped session queries (E6.S2)
    init {
        index(false, groupId)
    }
}
