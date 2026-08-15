package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for assistant-specific message data.
 * Contains properties that only apply to messages from the assistant.
 *
 * @property messageId Reference to the parent message in ChatMessageTable (primary key)
 * @property modelId Reference to the LLM model used for the message
 * @property settingsId Reference to the model settings used for the message
 * @property agentRoleId Optional reference to the agent role used for the message. Null when the
 *            message was not produced through an agent role; `SET NULL` when the role is deleted so
 *            provenance survives role deletion.
 * @property reasoningItemsJson JSON array of raw reasoning output items emitted with the message, for
 *            Responses-capable models. Opaque and nullable; must not be logged or rendered.
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
    val agentRoleId = reference(
        "agent_role_id",
        AgentRoleTable,
        onDelete = ReferenceOption.SET_NULL
    ).nullable()
    val reasoningItemsJson = text("reasoning_items_json").nullable()

    // Make messageId the primary key
    override val primaryKey = PrimaryKey(messageId)
}
