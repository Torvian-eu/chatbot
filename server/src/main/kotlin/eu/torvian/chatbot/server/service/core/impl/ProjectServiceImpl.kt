package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.MAX_PROJECT_NAME_LENGTH
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectAgentRoleDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.ProjectOwnershipDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.project.ProjectError as ProjectDaoError
import eu.torvian.chatbot.server.data.entities.ProjectEntity
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.CreateProjectError
import eu.torvian.chatbot.server.service.core.error.project.DeleteProjectError
import eu.torvian.chatbot.server.service.core.error.project.ProjectError
import eu.torvian.chatbot.server.service.core.error.project.UpdateProjectError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [ProjectService] providing owner-scoped project CRUD operations.
 *
 * Uses Arrow's `either { }`/`ensure`/`withError` pattern for typed logical errors and wraps all
 * operations in the shared [TransactionScope], mirroring [AgentRoleServiceImpl]. The member-role ids
 * are loaded batch-wise via `ProjectAgentRoleDao` (the single nullable `agent_roles.project_id`
 * column) so list and detail reads avoid an N+1.
 *
 * @property projectDao DAO for the `projects` table.
 * @property projectOwnershipDao DAO for the `project_owners` table (per-user ownership).
 * @property projectAgentRoleDao DAO for the project ↔ role membership relation, stored as the
 *            single nullable `agent_roles.project_id` column (member role ids).
 * @property agentRoleDao DAO used to validate member-role references (existence and ownership).
 * @property sessionDao DAO used to restore the Session Legality Invariant when a project is deleted or
 *            its role membership is edited.
 * @property transactionScope Transaction wrapper that keeps validation + persistence atomic.
 */
class ProjectServiceImpl(
    private val projectDao: ProjectDao,
    private val projectOwnershipDao: ProjectOwnershipDao,
    private val projectAgentRoleDao: ProjectAgentRoleDao,
    private val agentRoleDao: AgentRoleDao,
    private val sessionDao: SessionDao,
    private val transactionScope: TransactionScope
) : ProjectService {

    companion object {
        /** Logger used for service-level diagnostics. */
        private val logger: Logger = LogManager.getLogger(ProjectServiceImpl::class.java)
    }

    override suspend fun getAllProjectsForUser(userId: Long): List<ProjectDto> = transactionScope.transaction {
        logger.debug("Retrieving projects for user $userId")
        val entities = projectDao.getAllProjectsForUser(userId)
        val projectIds = entities.map { it.id }
        // Batch-load every project's member role ids in one query so the list endpoint avoids an N+1
        // read (mirrors the agent-role tools/spawnable batch pattern).
        val roleIdsByProject = projectAgentRoleDao.getRoleIdsForProjects(projectIds)
        entities.map { it.toDto(roleIdsByProject[it.id].orEmpty()) }
    }

    override suspend fun getProjectById(
        userId: Long,
        projectId: Long
    ): Either<ProjectError.NotFound, ProjectDto> =
        transactionScope.transaction {
            either {
                val entity = loadOwnedProject(userId, projectId, ProjectError.NotFound(projectId))
                entity.toDto(projectAgentRoleDao.getRoleIdsForProject(entity.id))
            }
        }

    override suspend fun createProject(
        userId: Long,
        request: CreateProjectRequest
    ): Either<CreateProjectError, ProjectDto> = transactionScope.transaction {
        either {
            logger.info("Creating project '${request.name}' for user $userId")

            validateName(request.name, request.name) { name, reason ->
                CreateProjectError.InvalidName(name, reason)
            }

            // Names are unique per owner user (not globally): only the requesting user's projects
            // matter, so different users may freely reuse the same name.
            ensure(!projectDao.projectNameExistsForUser(userId, request.name)) {
                CreateProjectError.NameAlreadyExists(request.name)
            }

            // Every requested member role must exist and belong to the requesting user; a missing or
            // foreign id collapses to the same not-found error (no existence leak, mirrors the
            // agent-role project validation). A role cannot belong to another project yet (a brand-new
            // project has no members), so any project-bound role is rejected too — attaching it would
            // silently move it.
            validateAgentRoleIds(
                userId = userId,
                roleIds = request.agentRoleIds,
                projectId = null,
                roleNotFound = { roleId -> CreateProjectError.RoleNotFound(roleId) },
                roleInAnotherProject = { roleId -> CreateProjectError.RoleInAnotherProject(roleId) }
            )

            val entity = projectDao.insertProject(request.name, request.description)

            withError({ ownershipError: SetOwnerError ->
                CreateProjectError.OwnerInsertFailed(ownershipError.toString())
            }) {
                projectOwnershipDao.setOwner(entity.id, userId).bind()
            }

            // Persist the requested membership as a full replacement of the new project's (empty)
            // role set, atomically with the row, ownership and validation.
            projectAgentRoleDao.replaceRolesForProject(entity.id, request.agentRoleIds)

            // Legality sweep for the attach direction (the same rule as on the role side), same
            // transaction as the membership write: attaching a role makes it project-bound, so any
            // session still using it while selecting a DIFFERENT project (or no project at all)
            // becomes illegal — exactly the "role became associated while the session has no project"
            // case.
            val illegalSessionIds = sessionDao
                .getSessionProjectPairsForRoles(request.agentRoleIds.toList())
                .filter { it.projectId != entity.id }
                .map { it.sessionId }
            sessionDao.clearAgentRoleForSessions(illegalSessionIds)

            logger.info("Created project '${request.name}' (id ${entity.id}) for user $userId")
            entity.toDto(request.agentRoleIds)
        }
    }

    override suspend fun updateProject(
        userId: Long,
        projectId: Long,
        request: UpdateProjectRequest
    ): Either<UpdateProjectError, ProjectDto> = transactionScope.transaction {
        either {
            logger.info("Updating project $projectId for user $userId")

            val existing = loadOwnedProject(userId, projectId, UpdateProjectError.NotFound(projectId))

            validateName(request.name, request.name) { name, reason ->
                UpdateProjectError.InvalidName(name, reason)
            }

            // Rename-uniqueness is scoped per owner; the project being updated is excluded implicitly:
            // it still carries its old name here, so a conflict means a DIFFERENT project of the same
            // owner owns the requested name.
            ensure(
                request.name == existing.name ||
                    !projectDao.projectNameExistsForUser(userId, request.name)
            ) {
                UpdateProjectError.NameAlreadyExists(request.name)
            }

            // Every member role of the replacement set must exist and belong to the requesting user;
            // a missing or foreign id collapses to the same not-found error. A role that belongs to a
            // DIFFERENT project is rejected: with single-project membership, attaching it here would
            // silently steal it from its project, so the caller must detach it there first.
            validateAgentRoleIds(
                userId = userId,
                roleIds = request.agentRoleIds,
                projectId = projectId,
                roleNotFound = { roleId -> UpdateProjectError.RoleNotFound(roleId) },
                roleInAnotherProject = { roleId -> UpdateProjectError.RoleInAnotherProject(roleId) }
            )

            val updated = existing.copy(name = request.name, description = request.description)

            withError({ _: ProjectDaoError.NotFound -> UpdateProjectError.NotFound(projectId) }) {
                projectDao.updateProject(updated).bind()
            }

            // The membership is a full replacement from the project side too: the UI can attach or
            // detach roles in one project edit, atomically with the row update.
            projectAgentRoleDao.replaceRolesForProject(projectId, request.agentRoleIds)

            // Legality sweep, same transaction as the membership write. Two directions must be restored:
            //  (1) detach — a session selecting this project whose role left the membership would be
            //      (project, role)-illegal; clear those roles.
            //  (2) attach — a session using any newly attached role while selecting a DIFFERENT
            //      project (or none) would be (role, project)-illegal: the role became project-bound
            //      while the session is elsewhere, project-less sessions included (the same clear rule
            //      as on the role side).
            val detachSessionIds = sessionDao
                .getSessionRolePairsForProject(projectId)
                .mapNotNull { pair ->
                    pair.agentRoleId
                        ?.takeIf { it !in request.agentRoleIds }
                        ?.let { pair.sessionId }
                }
            val attachSessionIds = sessionDao
                .getSessionProjectPairsForRoles(request.agentRoleIds.toList())
                .filter { it.projectId != projectId }
                .map { it.sessionId }
            sessionDao.clearAgentRoleForSessions(detachSessionIds + attachSessionIds)

            logger.info("Updated project $projectId for user $userId")
            updated.toDto(request.agentRoleIds)
        }
    }

    override suspend fun deleteProject(userId: Long, projectId: Long): Either<DeleteProjectError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Deleting project $projectId for user $userId")

                val existing = loadOwnedProject(userId, projectId, DeleteProjectError.NotFound(projectId))

                // Capture the affected sessions BEFORE the delete: after the FK nulls their
                // `project_id`, they would no longer be addressable by project. Their roles must be
                // cleared in the same transaction (uniform clear) so no session is left illegal.
                val affectedSessionIds = sessionDao.getSessionIdsByProject(existing.id)

                withError({ _: ProjectDaoError.NotFound -> DeleteProjectError.NotFound(projectId) }) {
                    projectDao.deleteProject(projectId).bind()
                }

                // The captured set is cleared uniformly regardless of each role's remaining memberships
                // (the conditional-keep variant was explicitly rejected): deleting a project makes the
                // affected sessions inert until a role is re-selected.
                sessionDao.clearAgentRoleForSessions(affectedSessionIds)

                logger.info(
                    "Deleted project $projectId for user $userId" +
                        if (affectedSessionIds.isEmpty()) "" else " (cleared ${affectedSessionIds.size} session role(s))"
                )
            }
        }

    // --- Validation helpers ---

    /**
     * Validates the project name shape (non-blank, at most 255 characters).
     *
     * @param name The name to validate.
     * @param rawName The original name (used for error reporting).
     * @param invalidName Factory building the caller's invalid-name error.
     * @return `null` on success or an error of type `E` via the raise scope.
     */
    private fun <E> Raise<E>.validateName(
        name: String,
        rawName: String,
        invalidName: (name: String, reason: String) -> E
    ) {
        ensure(name.isNotBlank()) {
            invalidName(rawName, "Project name cannot be blank")
        }
        ensure(name.length <= MAX_PROJECT_NAME_LENGTH) {
            invalidName(rawName, "Project name cannot exceed $MAX_PROJECT_NAME_LENGTH characters")
        }
    }

    /**
     * Validates that every role id in [roleIds] exists and belongs to [userId], and that none of
     * them is already bound to a DIFFERENT project than the one being populated.
     *
     * A missing or foreign id raises the caller-provided [roleNotFound] error — the same collapse
     * used by the agent-role paths, so an ownership mismatch never leaks as a distinct error. A role
     * whose single `projectId` is non-null and differs from [projectId] raises [roleInAnotherProject]:
     * with single-project membership, populating this project with it would silently move it out of
     * its current project.
     *
     * @param userId The user whose role ownership is required.
     * @param roleIds The role ids to validate; an empty set is the default member set and has nothing
     *            to check.
     * @param projectId The project being populated; null for a brand-new project (no role can already
     *            belong to it, so any project-bound role is rejected).
     * @param roleNotFound Factory building the caller's not-found error for a role id.
     * @param roleInAnotherProject Factory building the caller's role-bound-elsewhere error for a role id.
     * @return `null` on success or an error of type `E` via the raise scope.
     */
    private suspend fun <E> Raise<E>.validateAgentRoleIds(
        userId: Long,
        roleIds: Set<Long>,
        projectId: Long?,
        roleNotFound: (roleId: Long) -> E,
        roleInAnotherProject: (roleId: Long) -> E
    ) {
        if (roleIds.isEmpty()) {
            // The default empty set is a valid full replacement (clears membership); nothing to check.
            return
        }
        val ownedRoles = agentRoleDao
            .getRolesByIdsForUser(userId, roleIds.toList())
        val ownedRoleIds = ownedRoles.map { it.id }.toSet()
        roleIds.firstOrNull { it !in ownedRoleIds }?.let { missingId ->
            raise(roleNotFound(missingId))
        }
        // Same-project guard (single-project membership): a role already bound to a DIFFERENT project
        // cannot be attached here; the caller must detach it from its current project first.
        ownedRoles.firstOrNull { role -> role.projectId != null && role.projectId != projectId }?.let {
            raise(roleInAnotherProject(it.id))
        }
    }

    // --- Ownership helpers ---

    /**
     * Loads a project and verifies that [userId] owns it.
     *
     * Ownership mismatches are reported as the provided [notFoundError] so the service does not leak
     * the existence of projects owned by other users, and the caller's error surface stays uniform.
     *
     * @param userId The requesting user.
     * @param projectId The project to load.
     * @param notFoundError The not-found error to raise when the project is missing or not owned.
     * @return The [ProjectEntity] when owned, or a not-found error via the raise scope.
     */
    private suspend fun <E> Raise<E>.loadOwnedProject(
        userId: Long,
        projectId: Long,
        notFoundError: E
    ): ProjectEntity {
        val entity = withError({ _: ProjectDaoError.NotFound -> notFoundError }) {
            projectDao.getProjectById(projectId).bind()
        }
        ensureOwnedBy(userId, entity.id, notFoundError)
        return entity
    }

    /**
     * Ensures the given user owns the given project.
     *
     * @param userId The requesting user.
     * @param projectId The project to check.
     * @param notFoundError The not-found error to raise on ownership mismatch.
     */
    private suspend fun <E> Raise<E>.ensureOwnedBy(userId: Long, projectId: Long, notFoundError: E) {
        val ownerId = withError({ _: GetOwnerError -> notFoundError }) {
            projectOwnershipDao.getOwner(projectId).bind()
        }
        ensure(ownerId == userId) { notFoundError }
    }

    // --- Mapping helpers ---

    /**
     * Converts a stored project row into its wire [ProjectDto], carrying the member role ids.
     *
     * The ids come from the caller: batch-loaded by the list/detail paths, request-provided by the
     * create/update paths (the membership was just rewritten to equal the request's set).
     *
     * @receiver Stored project row to convert.
     * @param agentRoleIds The project's member role ids.
     * @return The corresponding [ProjectDto].
     */
    private fun ProjectEntity.toDto(agentRoleIds: Set<Long>): ProjectDto = ProjectDto(
        id = id,
        name = name,
        description = description,
        createdAt = createdAt,
        agentRoleIds = agentRoleIds
    )
}