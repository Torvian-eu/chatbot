package eu.torvian.chatbot.server.data.dao

/**
 * Persists per-user disabled markers for agent roles.
 *
 * Presence of a `(user, role)` row means the role is disabled for that user; absence means enabled.
 * Every operation is scoped by the requesting userId — user A's markers never affect user B's
 * reads or writes — which is exactly what future shared roles need (each sharing-visible user keeps
 * their own enabled/disabled state on the side table, never on the role row).
 */
interface AgentRoleDisabledDao {
    /**
     * Batch-loads the role ids that are disabled **for [userId]** from the given role-id list.
     *
     * Mirrors [AgentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles]: the list path stays N+1-free
     * (one query). Single-role paths should use [isRoleDisabled] rather than a one-element list. The
     * composite PK's role_id prefix covers this query shape; no extra index is needed.
     *
     * @param userId User whose disabled state is requested.
     * @param roleIds Role ids to test; an empty list short-circuits to an empty set.
     * @return The subset of [roleIds] that has a disabled row for [userId]; empty when none.
     */
    suspend fun getDisabledRoleIds(userId: Long, roleIds: List<Long>): Set<Long>

    /**
     * Whether [roleId] is disabled **for [userId]**.
     *
     * Single-row existence check for the user-scoped single-role paths (embedding [getDisabledRoleIds]
     * with a one-element list would be needlessly indirect). The composite PK's `role_id` prefix keeps
     * the lookup indexed; no extra index is needed.
     *
     * @param userId User whose disabled state is requested.
     * @param roleId Role to test.
     * @return `true` when a disabled row exists for the `(user, role)` pair, `false` otherwise.
     */
    suspend fun isRoleDisabled(userId: Long, roleId: Long): Boolean

    /**
     * Sets the disabled state of [roleId] for [userId], idempotently.
     *
     * `true` inserts the `(user, role)` row (no-op when the row already exists thanks to
     * delete-then-insert), `false` deletes it (no-op when absent). The delete-then-insert write
     * pattern matches the spawn allow-list DAO's replace-style semantics and stays atomic inside the
     * shared transaction.
     *
     * @param userId User the state applies to.
     * @param roleId Role whose per-user state is changed.
     * @param disabled New state: `true` disables the role for [userId], `false` re-enables it.
     */
    suspend fun setRoleDisabled(userId: Long, roleId: Long, disabled: Boolean)
}