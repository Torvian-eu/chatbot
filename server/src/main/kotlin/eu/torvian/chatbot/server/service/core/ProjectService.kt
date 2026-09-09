package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.service.core.error.project.CreateProjectError
import eu.torvian.chatbot.server.service.core.error.project.DeleteProjectError
import eu.torvian.chatbot.server.service.core.error.project.ProjectError
import eu.torvian.chatbot.server.service.core.error.project.UpdateProjectError

/**
 * Service interface for managing user-owned projects.
 *
 * Every operation is scoped to the requesting user, and the service verifies that the user owns the
 * project before returning or mutating it (a foreign or nonexistent project collapses to
 * [ProjectError.NotFound], so no existence leak exists). Projects group agent roles: the returned
 * [ProjectDto]s carry the member role ids, batch-loaded for list and detail reads.
 */
interface ProjectService {

    /**
     * Retrieves all projects owned by the user, each with its member role ids batch-loaded.
     *
     * @param userId The ID of the user whose projects to retrieve.
     * @return List of [ProjectDto] owned by the user; empty list if the user owns no projects.
     */
    suspend fun getAllProjectsForUser(userId: Long): List<ProjectDto>

    /**
     * Retrieves a single project by ID, verifying ownership.
     *
     * @param userId The ID of the requesting user.
     * @param projectId The ID of the project to retrieve.
     * @return Either [ProjectError.NotFound] if the project does not exist or is not owned by the
     *         user, or the [ProjectDto] with its member role ids.
     */
    suspend fun getProjectById(userId: Long, projectId: Long): Either<ProjectError.NotFound, ProjectDto>

    /**
     * Creates a new project owned by the user.
     *
     * Validates the name and enforces per-owner name uniqueness before persisting; the ownership
     * link is inserted atomically with the row, and the requested [CreateProjectRequest.agentRoleIds]
     * are attached as the project's initial member roles in the same transaction.
     *
     * @param userId The ID of the user who will own the project.
     * @param request The creation payload.
     * @return Either a [CreateProjectError] or the newly created [ProjectDto] carrying the member
     *         role ids.
     */
    suspend fun createProject(
        userId: Long,
        request: CreateProjectRequest
    ): Either<CreateProjectError, ProjectDto>

    /**
     * Updates an existing project owned by the user.
     *
     * The rename-uniqueness check excludes the project being updated, so keeping the current name is
     * always allowed. The [UpdateProjectRequest.agentRoleIds] membership is applied as a full
     * replacement in the same transaction, and sessions whose attached role left the project have
     * their role cleared (Session Legality Invariant), mirroring the role-update sweep.
     *
     * @param userId The ID of the requesting user.
     * @param projectId The ID of the project to update.
     * @param request The update payload.
     * @return Either an [UpdateProjectError] or the updated [ProjectDto] with its member role ids.
     */
    suspend fun updateProject(
        userId: Long,
        projectId: Long,
        request: UpdateProjectRequest
    ): Either<UpdateProjectError, ProjectDto>

    /**
     * Deletes a project owned by the user.
     *
     * The project's `project_owners` row cascades away and its member roles' `project_id` is nulled
     * (roles survive), and
     * `chat_sessions.project_id` becomes null via `ON DELETE SET NULL`. In the same transaction the
     * service clears the agent role of every session that selected the deleted project (uniform
     * legality restoration), so no session is left with an illegal pair. Does **not** delete roles.
     *
     * @param userId The ID of the requesting user.
     * @param projectId The ID of the project to delete.
     * @return Either a [DeleteProjectError] or Unit on success.
     */
    suspend fun deleteProject(userId: Long, projectId: Long): Either<DeleteProjectError, Unit>
}