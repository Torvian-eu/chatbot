package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for the agent-role ↔ tool-definition join.
 *
 * Replaces the former `agent_roles.tools_json` JSON-array column: the set of tool definitions
 * attached to an agent role is now a normalized relation with DB-enforced integrity.
 *
 * - The primary key `(role_id, tool_definition_id)` makes duplicate tool ids impossible at the DB
 *   level.
 * - `ON DELETE CASCADE` on both foreign keys: deleting a tool definition removes it from every role
 *   (no dangling ids, no application-level sweep), and deleting a role removes its tool rows.
 * - No ordering column: a role's tool set is deliberately unordered (mirrors `Set<Long>` on the wire),
 *   so a full replacement is a plain delete + insert with no ordering bookkeeping.
 *
 * @property roleId Reference to the owning agent role (`CASCADE` on delete).
 * @property toolDefinitionId Reference to the attached tool definition (`CASCADE` on delete).
 */
object AgentRoleToolsTable : Table("agent_role_tools") {
    val roleId = reference("role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)
    val toolDefinitionId =
        reference("tool_definition_id", ToolDefinitionTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(roleId, toolDefinitionId)

    init {
        // SQLite does not auto-create FK-column indexes on the child side; this index makes the
        // ON DELETE CASCADE from tool_definitions and reverse lookups ("which roles use tool X")
        // fast.
        index(isUnique = false, toolDefinitionId)
    }
}
