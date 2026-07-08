package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable.builtInToolName
import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable.toolDefinitionId
import eu.torvian.chatbot.server.data.tables.BuiltInToolDefinitionTable.workerId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Side-table linking built-in worker tools to their owning worker and the base tool definition.
 *
 * Built-in worker tools are dispatched directly to a worker over the `tool.call` protocol. The
 * server-side catalog stores the public [ToolDefinitionTable.name] (which may include a configured
 * prefix for disambiguation across workers) on the base row, while this table records the
 * unprefixed [builtInToolName] that the worker runtime uses to resolve the implementation.
 *
 * When a worker is deleted, all of its built-in tool definitions are removed (CASCADE on
 * [workerId]); when a tool definition is deleted independently, the linkage is removed but the
 * worker remains (CASCADE on [toolDefinitionId]).
 *
 * @property toolDefinitionId Reference to the base tool definition row (primary key + foreign key).
 * @property workerId Reference to the owning worker (required, not null).
 * @property builtInToolName Unprefixed internal worker runtime name used to look up the implementation.
 */
object BuiltInToolDefinitionTable : Table("built_in_tool_definitions") {
    val toolDefinitionId = reference(
        "tool_definition_id",
        ToolDefinitionTable,
        onDelete = ReferenceOption.CASCADE
    )
    val workerId = reference(
        "worker_id",
        WorkersTable,
        onDelete = ReferenceOption.CASCADE
    )
    val builtInToolName = varchar("built_in_tool_name", 255)

    override val primaryKey = PrimaryKey(toolDefinitionId)
}

