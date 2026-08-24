package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Side-table linking server built-in tools to their owning user and the base tool definition.
 *
 * Server built-in tools (e.g. `list_agent_roles`) are executed entirely in-process on the server:
 * after a plain approval the server runs the matching handler directly, with no worker dispatch and
 * no operator relay. Each user gets their own [ToolDefinitionTable] row, linked here by [userId],
 * mirroring the operator-tool pattern, so approval preferences
 * (`user_tool_approval_preferences`) and the per-user enable/disable flag stay user-scoped.
 *
 * When a user is deleted, all of their server built-in tool definitions are removed (CASCADE on
 * [userId]); when a tool definition is deleted independently, the linkage is removed but the user
 * remains (CASCADE on [toolDefinitionId]).
 *
 * The public [ToolDefinitionTable.name] is prefix-derived and seeder-owned; [builtInToolName]
 * stores the canonical, unprefixed catalog name used for deduplication and execution dispatch.
 * The column is nullable at the DB level (SQLite ALTER limitation) but the application always
 * writes it and the mappers fail loudly when it is null.
 *
 * @property toolDefinitionId Reference to the base tool definition row (primary key + foreign key).
 * @property userId Reference to the owning user (required, not null).
 * @property builtInToolName Canonical, unprefixed catalog name (e.g. `list_agent_roles`).
 */
object ServerBuiltInToolDefinitionTable : Table("server_builtin_tool_definitions") {
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
    val builtInToolName = varchar("built_in_tool_name", 255).nullable()

    override val primaryKey = PrimaryKey(toolDefinitionId)
}
