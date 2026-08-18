package eu.torvian.chatbot.server.data.dao

/**
 * Persists the role-to-role spawn allow-list.
 *
 * Ownership and target existence are validated by the role service before replacement; this DAO
 * remains focused on the normalized relation.
 */
interface AgentRoleSpawnableRoleDao {
    /**
     * Loads target role ids granted to [sourceRoleId].
     *
     * @param sourceRoleId Role whose grants are requested.
     * @return Target role ids, or an empty set when no grants exist.
     */
    suspend fun getSpawnableRoleIdsForRole(sourceRoleId: Long): Set<Long>

    /**
     * Batch-loads grants for several source roles.
     *
     * @param sourceRoleIds Source role ids to query.
     * @return Map containing each source role's target ids when it has grants.
     */
    suspend fun getSpawnableRoleIdsForRoles(sourceRoleIds: List<Long>): Map<Long, Set<Long>>

    /**
     * Replaces all grants for one source role atomically.
     *
     * @param sourceRoleId Role whose grants are replaced.
     * @param targetIds Target role ids to grant; replaces any existing grants.
     */
    suspend fun replaceSpawnableRolesForRole(sourceRoleId: Long, targetIds: Set<Long>)
}
