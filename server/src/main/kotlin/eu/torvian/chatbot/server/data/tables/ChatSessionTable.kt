package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

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
    val createdAt = long("created_at").default(System.currentTimeMillis())
    val updatedAt = long("updated_at").default(System.currentTimeMillis())
    val groupId = reference("group_id", ChatGroupTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val currentModelId = reference("current_model_id", LLMModelTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val currentSettingsId = reference("current_settings_id", ModelSettingsTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // Add index for groupId to speed up grouped session queries (E6.S2)
    init {
        index(false, groupId)
    }
}
