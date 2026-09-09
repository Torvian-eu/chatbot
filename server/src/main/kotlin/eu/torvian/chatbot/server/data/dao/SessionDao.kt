package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.server.data.dao.error.SessionError

/**
 * The persisted per-session project selection: the session's `project_id` together with its
 * `agent_role_id`.
 *
 * Read atomically by the project-selection flow so the legality of the existing role under the new
 * project can be evaluated in the same transaction as the write. Also returned to the client as
 * the resulting session state after the mutation.
 *
 * @property projectId The session's selected project id (null = no project).
 * @property agentRoleId The session's attached agent role id (null = no role).
 */
data class SessionProjectSelection(
    val projectId: Long?,
    val agentRoleId: Long?
)

/**
 * One session's `(agent_role_id = role, project_id)` pair, used by the role-update legality sweep.
 *
 * Sessions currently using a role are read with their project ids so the service can clear the role
 * where the pair became illegal after a `projectId` replacement.
 *
 * @property sessionId The session identifier.
 * @property projectId The session's selected project id (null = no project).
 */
data class SessionProjectPair(
    val sessionId: Long,
    val projectId: Long?
)

/**
 * One session's `(project_id = project, agent_role_id)` pair, used by the project-update sweep.
 *
 * Sessions currently selecting a project are read with their attached role ids so the service can
 * clear the role where the pair became illegal after an `agentRoleIds` membership replacement on the
 * project side.
 *
 * @property sessionId The session identifier.
 * @property agentRoleId The session's attached agent role id (null = no role).
 */
data class SessionRolePair(
    val sessionId: Long,
    val agentRoleId: Long?
)

/**
 * Data Access Object for ChatSession entities.
 */
interface SessionDao {
    /**
     * Retrieves a list of all chat session summaries, ordered by update time.
     * Includes group name via a join if assigned to a group.
     * @return A list of [ChatSessionSummary] objects.
     */
    suspend fun getAllSessions(): List<ChatSessionSummary>

    /**
     * Retrieves the full details of a specific chat session, including all its messages.
     * Messages are loaded separately and attached.
     * @param id The ID of the session to retrieve.
     * @return Either a [SessionError.SessionNotFound] or the [ChatSession] object with messages.
     */
    suspend fun getSessionById(id: Long): Either<SessionError.SessionNotFound, ChatSession>

    /**
     * Inserts a new chat session record into the database.
     * @param name The name for the new session.
     * @param groupId Optional ID of the group to assign the session to.
     * @param agentRoleId Optional ID of the agent role to select for the session.
     * @param projectId Optional ID of the project to select for the session. Cloning copies the
     *            original session's project id so a cloned legal session is born legal.
     * @return Either a [SessionError.ForeignKeyViolation] or the newly created [ChatSession] object.
     */
    suspend fun insertSession(
        name: String,
        groupId: Long? = null,
        agentRoleId: Long? = null,
        projectId: Long? = null
    ): Either<SessionError.ForeignKeyViolation, ChatSession>

    /**
     * Updates the name of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param name The new name for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionName(id: Long, name: String): Either<SessionError.SessionNotFound, Unit>

    /**
     * Updates the group ID of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param groupId The new optional group ID for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionGroupId(id: Long, groupId: Long?): Either<SessionError, Unit>

    /**
     * Updates the agent role selected for an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param agentRoleId The new optional agent role ID for the session. Null deselects the role.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionAgentRoleId(id: Long, agentRoleId: Long?): Either<SessionError, Unit>

    /**
     * Updates the project selected for an existing chat session.
     * Also updates the `updatedAt` timestamp.
     *
     * The caller (SessionService) is responsible for the Session Legality Invariant: when the new
     * selection makes the attached role illegal, the role is cleared in the same transaction. A
     * foreign project id (e.g. one that was deleted concurrently) surfaces as
     * [SessionError.ForeignKeyViolation], which the service maps to a project-not-found error.
     *
     * @param id The ID of the session to update.
     * @param projectId The new optional project ID for the session. Null deselects the project.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionProjectId(id: Long, projectId: Long?): Either<SessionError, Unit>

    /**
     * Reads a session's current project and agent-role selection without loading messages.
     *
     * @param id The ID of the session to read.
     * @return Either [SessionError.SessionNotFound] or the [SessionProjectSelection].
     */
    suspend fun getSessionProjectSelection(id: Long): Either<SessionError.SessionNotFound, SessionProjectSelection>

    /**
     * Returns every session currently using the given role, with its selected project id (read by
     * the role-update legality sweep).
     *
     * Sessions without a selected project appear with a null [SessionProjectPair.projectId].
     *
     * @param roleId The role identifier.
     * @return The (sessionId, projectId) pairs; empty list if no session uses the role.
     */
    suspend fun getSessionProjectPairsForRole(roleId: Long): List<SessionProjectPair>

    /**
     * Batch variant of [getSessionProjectPairsForRole]: returns every session currently using ANY of
     * the given roles, with its selected project id, in a single query (avoids an N+1 read when
     * several roles are attached in one write, e.g. the project-side membership sweep).
     *
     * A session carries at most one role, so a session cannot be matched twice and appears at most
     * once in the result. Sessions without a selected project appear with a null
     * [SessionProjectPair.projectId].
     *
     * @param roleIds The role identifiers to read; an empty list produces an empty result.
     * @return The (sessionId, projectId) pairs; empty list if no session uses any of the roles.
     */
    suspend fun getSessionProjectPairsForRoles(roleIds: List<Long>): List<SessionProjectPair>

    /**
     * Clears the agent role of the given sessions (`agent_role_id = NULL`) and bumps `updated_at`.
     *
     * Used by the legality-restoring sweeps: role updates that make a session's pair illegal and
     * project deletion affecting sessions. A no-op on an empty list, so callers can skip the
     * explicit emptiness check.
     *
     * @param sessionIds The sessions to clear the role on.
     */
    suspend fun clearAgentRoleForSessions(sessionIds: List<Long>)

    /**
     * Returns the ids of every session whose `project_id` is the given project (the affected
     * sessions read by project deletion).
     *
     * @param projectId The project identifier.
     * @return The session ids; empty list if no session selects the project.
     */
    suspend fun getSessionIdsByProject(projectId: Long): List<Long>

    /**
     * Returns every session currently selecting the given project together with its attached agent
     * role (project-update sweep read).
     *
     * Sessions without an attached role appear with a null [SessionRolePair.agentRoleId].
     *
     * @param projectId The project identifier.
     * @return The (sessionId, agentRoleId) pairs; empty list if no session selects the project.
     */
    suspend fun getSessionRolePairsForProject(projectId: Long): List<SessionRolePair>

    /**
     * Updates the current leaf message ID of an existing chat session.
     * Also updates the `updatedAt` timestamp.
     * @param id The ID of the session to update.
     * @param messageId The new optional leaf message ID for the session.
     * @return Either a [SessionError] or Unit if successful.
     */
    suspend fun updateSessionLeafMessageId(id: Long, messageId: Long?): Either<SessionError, Unit>

    /**
     * Deletes a chat session by ID.
     * Relies on the database's foreign key CASCADE constraint to delete associated messages.
     * @param id The ID of the session to delete.
     * @return Either a [SessionError.SessionNotFound] or Unit if successful.
     */
    suspend fun deleteSession(id: Long): Either<SessionError.SessionNotFound, Unit>

    /**
     * Updates the `groupId` for all sessions currently assigned to a specific group,
     * setting their `groupId` to null (ungrouping them).
     * Used by the `GroupService` when a group is deleted.
     * @param groupId The ID of the group whose sessions should be ungrouped.
     */
    suspend fun ungroupSessions(groupId: Long)
}
