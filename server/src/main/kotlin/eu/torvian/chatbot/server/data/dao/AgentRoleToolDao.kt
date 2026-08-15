package eu.torvian.chatbot.server.data.dao

/**
 * Data Access Object for the `agent_role_tools` join table.
 *
 * Owns the normalized role↔tool relation that replaced the former `agent_roles.tools_json` column.
 * The tool set is deliberately unordered — it mirrors `Set<Long>` on the wire, so the primary key
 * makes duplicates impossible at the DB level. All operations are plain `suspend` functions: a full
 * replacement has no "not found" semantics (role existence is checked by the caller), so no Arrow
 * error surface is needed. Unknown tool ids are rejected by the tool foreign key at the DB level; the
 * service pre-validates ids first so users get the friendly `ToolNotFound` error.
 */
interface AgentRoleToolDao {

    /**
     * Returns the tool-definition ids attached to one role.
     *
     * @param roleId The role identifier.
     * @return The tool ids as a set; empty set if the role has no tools (or does not exist).
     */
    suspend fun getToolsForRole(roleId: Long): Set<Long>

    /**
     * Batch variant of [getToolsForRole] for list endpoints: loads the tool ids of many roles in a
     * single query, avoiding an N+1 read per role.
     *
     * @param roleIds The role identifiers to load tools for.
     * @return A map from role id to its tool-id set. Roles with no tools may be absent from the map.
     */
    suspend fun getToolsForRoles(roleIds: List<Long>): Map<Long, Set<Long>>

    /**
     * Full replacement of a role's tool set: deletes all existing rows of the role and inserts
     * [toolIds] as new rows.
     *
     * Runs as one atomic operation. Unknown tool ids are rejected by the tool foreign key.
     *
     * @param roleId The role identifier.
     * @param toolIds The new tool-definition ids.
     */
    suspend fun replaceToolsForRole(roleId: Long, toolIds: Set<Long>)

    /**
     * Reverse lookup — returns every role id that references the given tool definition.
     *
     * Used by admin/delete-confirmation flows to answer "which roles would break if I delete tool X?".
     *
     * @param toolId The tool-definition identifier.
     * @return The role ids referencing the tool; empty list if none do.
     */
    suspend fun getRoleIdsUsingTool(toolId: Long): List<Long>
}
