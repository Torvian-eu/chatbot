package eu.torvian.chatbot.server.data.dao

/**
 * Data Access Object for the project ↔ agent-role membership relation.
 *
 * Owns the project side of the membership. The relation is deliberate one-to-many: a project has a
 * SET of member roles, and each role belongs to at most one project — the role side is stored as the
 * single nullable `agent_roles.project_id` column (null = unassociated), so no join table exists.
 * Reads return sets of role ids per project (mirroring `ProjectDto.agentRoleIds` on the wire), and
 * project create/update rewrite the membership as a full replacement from the project side
 * ([replaceRolesForProject]). All operations are plain `suspend` functions: a full replacement has
 * no "not found" semantics (project/role existence is checked by the caller), so no Arrow error
 * surface is needed. Unknown ids are rejected by the foreign keys at the DB level; the services
 * pre-validate ids first so users get the friendly not-found errors.
 */
interface ProjectAgentRoleDao {

    /**
     * Reverse lookup — returns the role ids belonging to one project.
     *
     * @param projectId The project identifier.
     * @return The member role ids as a set; empty set if the project has no roles.
     */
    suspend fun getRoleIdsForProject(projectId: Long): Set<Long>

    /**
     * Batch variant of [getRoleIdsForProject] for list endpoints: loads the memberships of many
     * projects in a single query, avoiding an N+1 read per project.
     *
     * @param projectIds The project identifiers to load memberships for.
     * @return A map from project id to its member role-id set. Projects with no roles may be absent
     *         from the map.
     */
    suspend fun getRoleIdsForProjects(projectIds: List<Long>): Map<Long, Set<Long>>

    /**
     * Full replacement of a project's role set from the project side: roles that belonged to the
     * project but are absent from [roleIds] become unassociated (`project_id = NULL`), and roles in
     * [roleIds] are attached to the project.
     *
     * Runs as one atomic operation. The caller must have validated that every role in [roleIds] is
     * unassociated or already belongs to this project — attaching a role that belongs to a DIFFERENT
     * project would silently move it, which the services reject before reaching this DAO.
     *
     * @param projectId The project identifier.
     * @param roleIds The new member role ids.
     */
    suspend fun replaceRolesForProject(projectId: Long, roleIds: Set<Long>)
}