package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Side-table linking operator-executed tools to their owning user and the base tool definition.
 *
 * Operator tools (e.g. `spawn_agent`) are server-orchestrated: after approval the server relays the
 * tool call to the operator, who runs the tool and returns the result over the chat WebSocket. Each
 * user gets their own [ToolDefinitionTable] row, linked here by [userId], so approval preferences
 * (`user_tool_approval_preferences`) and the per-user enable/disable flag stay user-scoped.
 *
 * When a user is deleted, all of their operator tool definitions are removed (CASCADE on [userId]);
 * when a tool definition is deleted independently, the linkage is removed but the user remains
 * (CASCADE on [toolDefinitionId]).
 *
 * @property toolDefinitionId Reference to the base tool definition row (primary key + foreign key).
 * @property userId Reference to the owning user (required, not null).
 */
object OperatorToolDefinitionTable : Table("operator_tool_definitions") {
    val toolDefinitionId = reference(
        "tool_definition_id",
        ToolDefinitionTable,
        onDelete = ReferenceOption.CASCADE
    )
    val userId = reference(
        "user_id",
        UsersTable,
        onDelete = ReferenceOption.CASCADE
    )

    override val primaryKey = PrimaryKey(toolDefinitionId)
}
