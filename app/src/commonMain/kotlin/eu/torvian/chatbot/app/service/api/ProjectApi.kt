package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * API client interface for user-owned project management.
 *
 * This interface defines the operations for CRUD against the `/api/v1/projects` endpoints.
 * Projects are owned per-user (the creating user becomes the sole owner); every endpoint is
 * reachable with a `USER_JWT`. All methods return [Either<ApiResourceError, T>] so callers handle
 * failures explicitly.
 */
interface ProjectApi {

    /**
     * Retrieves all projects accessible to the current user.
     *
     * Corresponds to `GET /api/v1/projects`. Each returned [ProjectDto] carries its member
     * `agentRoleIds` so list views can render membership without extra round trips.
     *
     * @return [Either.Right] containing the list of [ProjectDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getAllProjects(): Either<ApiResourceError, List<ProjectDto>>

    /**
     * Retrieves a single project owned by the current user.
     *
     * Corresponds to `GET /api/v1/projects/{projectId}`.
     *
     * @param projectId The unique identifier of the project to fetch.
     * @return [Either.Right] containing the requested [ProjectDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun getProjectById(projectId: Long): Either<ApiResourceError, ProjectDto>

    /**
     * Creates a new project; the requesting user becomes its sole owner.
     *
     * Corresponds to `POST /api/v1/projects`. The optional [CreateProjectRequest.agentRoleIds] set
     * is persisted as the project's initial role membership in the same transaction.
     *
     * @param request The full configuration of the project to create.
     * @return [Either.Right] containing the newly created [ProjectDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun createProject(request: CreateProjectRequest): Either<ApiResourceError, ProjectDto>

    /**
     * Replaces the configuration (name, description, member roles) of an existing project.
     *
     * Corresponds to `PUT /api/v1/projects/{projectId}`. The update is a full replacement, not a patch.
     *
     * @param projectId The unique identifier of the project to update.
     * @param request The replacement configuration.
     * @return [Either.Right] containing the updated [ProjectDto] on success, or
     *         [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun updateProject(projectId: Long, request: UpdateProjectRequest): Either<ApiResourceError, ProjectDto>

    /**
     * Deletes a project and its ownership/role links. Member roles themselves are not deleted;
     * sessions using the project are unassigned server-side via `SET NULL`.
     *
     * Corresponds to `DELETE /api/v1/projects/{projectId}`.
     *
     * @param projectId The unique identifier of the project to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] containing an [ApiResourceError] on failure.
     */
    suspend fun deleteProject(projectId: Long): Either<ApiResourceError, Unit>
}