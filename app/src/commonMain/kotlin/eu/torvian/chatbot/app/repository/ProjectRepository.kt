package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for user-owned projects with reactive data streams.
 *
 * This repository is the single source of truth for the current user's projects. It exposes
 * [projects] as a reactive [StateFlow] and keeps that stream in sync after every CRUD operation,
 * following the same pattern as [AgentRoleRepository].
 */
interface ProjectRepository {

    /**
     * Reactive stream of all projects owned by the current user.
     *
     * This StateFlow provides real-time updates whenever project data changes, allowing ViewModels
     * and the chat top bar to react without manual refresh operations.
     *
     * @return StateFlow containing the current state of the project list wrapped in [DataState].
     */
    val projects: StateFlow<DataState<RepositoryError, List<ProjectDto>>>

    /**
     * Loads all projects from the server and updates [projects].
     *
     * This operation fetches the latest project data from the backend and updates the reactive
     * stream. If a load is already in progress, the call returns immediately without starting a
     * duplicate request.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun loadProjects(): Either<RepositoryError, Unit>

    /**
     * Loads the details of a single project and upserts it into [projects].
     *
     * The server resolves `agentRoleIds` on every read, so this is used to refresh a project after
     * membership edits made elsewhere.
     *
     * @param projectId The unique identifier of the project to load.
     * @return [Either.Right] with the loaded [ProjectDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun loadProjectDetails(projectId: Long): Either<RepositoryError, ProjectDto>

    /**
     * Creates a new project from a full configuration request.
     *
     * Validation stays server-side; this repository only maps API errors to [RepositoryError].
     * After successful creation the new project is appended to [projects].
     *
     * @param request The full configuration of the project to create.
     * @return [Either.Right] with the created [ProjectDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun createProject(request: CreateProjectRequest): Either<RepositoryError, ProjectDto>

    /**
     * Replaces the configuration of an existing project.
     *
     * After successful update the updated project replaces the previous entry in [projects].
     *
     * @param projectId The unique identifier of the project to update.
     * @param request The replacement configuration.
     * @return [Either.Right] with the updated [ProjectDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun updateProject(projectId: Long, request: UpdateProjectRequest): Either<RepositoryError, ProjectDto>

    /**
     * Clones an existing project under a new, server-unique name.
     *
     * The server deep-copies the source project's member agent roles as new role rows bound to the
     * clone (the source is left untouched); this repository only maps API errors to [RepositoryError]
     * and upserts the cloned project into [projects] (replace-if-present, else append).
     *
     * @param projectId The unique identifier of the source project to clone.
     * @param request The clone payload: new name (required) and optional description override.
     * @return [Either.Right] with the cloned [ProjectDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun cloneProject(projectId: Long, request: CloneProjectRequest): Either<RepositoryError, ProjectDto>

    /**
     * Deletes a project and removes it from [projects].
     *
     * Sessions referencing the project are unassigned server-side via `SET NULL`; roles on affected
     * sessions are cleared by the server in the same transaction.
     *
     * @param projectId The unique identifier of the project to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun deleteProject(projectId: Long): Either<RepositoryError, Unit>
}