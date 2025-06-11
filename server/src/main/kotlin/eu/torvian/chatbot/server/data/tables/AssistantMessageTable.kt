package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

/**
 * Exposed table definition for assistant-specific message data.
 * Contains properties that only apply to messages from the assistant.
 *
 * @property messageId Reference to the parent message in ChatMessageTable (primary key)
 * @property modelId Reference to the LLM model used for the message
 * @property settingsId Reference to the model settings used for the message
 */
object AssistantMessageTable : Table("assistant_messages") {
    val messageId = reference(
        "message_id",
        ChatMessageTable,
        onDelete = ReferenceOption.CASCADE
    )
    val modelId = reference(
        "model_id",
        LLMModelTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
    val settingsId = reference(
        "settings_id",
        ModelSettingsTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()

    // Make messageId the primary key
    override val primaryKey = PrimaryKey(messageId)
}