package eu.torvian.chatbot.server.data.dao

/**
 * Data Access Object for the `worker_credential_profile_agent_roles` join table.
 *
 * Owns the many-to-many relation between a credential profile and the agent roles allowed to attach
 * it. The role set is deliberately unordered — it mirrors `Set<Long>` on the wire — so the composite
 * primary key makes duplicates impossible at the DB level. All operations are plain `suspend`
 * functions: a full replacement has no "not found" semantics (profile existence is checked by the
 * caller), and role existence is validated by the service before a write so users get a typed error
 * rather than a foreign-key violation.
 */
interface WorkerCredentialProfileAgentRoleDao {

    /**
     * Returns the role ids linked to one profile.
     *
     * @param profileId The profile identifier.
     * @return The linked role ids ascending; empty when the profile has none or does not exist.
     */
    suspend fun getAgentRoleIds(profileId: Long): Set<Long>

    /**
     * Batch variant of [getAgentRoleIds] for list reads, avoiding an N+1 query per profile.
     *
     * @param profileIds The profile identifiers to load links for.
     * @return A map from profile id to its ascending role ids. Profiles with no links may be absent.
     */
    suspend fun getAgentRoleIdsByProfileIds(profileIds: List<Long>): Map<Long, Set<Long>>

    /**
     * Reverse lookup: returns every profile linked to one role.
     *
     * Used by the advertisement loader, which scopes profiles to the mapped role.
     *
     * @param agentRoleId The agent role identifier.
     * @return The profile ids linked to the role; empty when none are.
     */
    suspend fun getProfileIdsByAgentRoleId(agentRoleId: Long): Set<Long>

    /**
     * Full replacement of a profile's linked role set: deletes its existing rows and inserts
     * [agentRoleIds].
     *
     * Runs as one atomic operation, so a failed insert leaves the previous set untouched.
     *
     * @param profileId The profile identifier.
     * @param agentRoleIds The new role ids to link.
     */
    suspend fun replaceAgentRoleIds(profileId: Long, agentRoleIds: Set<Long>)
}
