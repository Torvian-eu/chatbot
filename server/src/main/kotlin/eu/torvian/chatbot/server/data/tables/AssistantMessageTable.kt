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
 * @property reasoningItemsJson JSON array of replay-safe reasoning output items emitted with the message, for
 *            Responses-capable models. Opaque and nullable; must not be logged or rendered.
 * @property isComplete Whether the assistant message completed normally. `NOT NULL DEFAULT TRUE` so every row
 *            created before this column existed (and every row inserted without an explicit state) reads as
 *            completed.
 * @property incompleteCause Enum *name* of the terminal incompletion cause (`INTERRUPTED_BY_USER` or
 *            `FAILED`), or `NULL` when no terminal cause is known (completed message, in-flight streaming
 *            placeholder, or a row abandoned by a crash). Stored as text rather than an enumerated column so a
 *            corrupt or unknown value can degrade to "no cause" instead of failing a whole session read.
 * @property errorCode Enum *name* of the machine-readable failure classification, populated only together with
 *            `FAILED`; `NULL` otherwise.
 * @property errorMessage Bounded, user-facing failure reason (no provider bodies or exception text), populated
 *            only together with `FAILED`; `NULL` for a user interruption.
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
    val isComplete = bool("is_complete").default(true)
    val incompleteCause = varchar("incomplete_cause", 50).nullable()
    val errorCode = varchar("error_code", 50).nullable()
    val errorMessage = text("error_message").nullable()

    // Make messageId the primary key
    override val primaryKey = PrimaryKey(messageId)
}
