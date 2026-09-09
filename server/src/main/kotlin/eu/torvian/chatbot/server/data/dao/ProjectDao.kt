package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.project.ProjectError
import eu.torvian.chatbot.server.data.entities.ProjectEntity

/**
 * Data Access Object for project entities.
 *
 * Project-name uniqueness is **per owner user**, not global: different users may reuse the same name.
 * The DB cannot express that constraint (ownership lives in the separate `project_owners` table), so
 * the per-user uniqueness checks live at the service layer; the DAO exposes
 * [projectNameExistsForUser] and owner-scoped reads to support them. The project's member-role ids
 * are stored as the single nullable `agent_roles.project_id` column and managed through
 * [ProjectAgentRoleDao]; the
 * owner is stored in `project_owners` and managed through [ProjectOwnershipDao].
 */
interface ProjectDao {

    /**
     * Retrieves all projects owned by the given user, joined through the ownership table.
     *
     * @param userId ID of the owner user.
     * @return List of [ProjectEntity] owned by the user; empty list if the user owns no projects.
     */
    suspend fun getAllProjectsForUser(userId: Long): List<ProjectEntity>

    /**
     * Retrieves a project by its unique ID.
     *
     * The caller is responsible for ownership verification (see [getProjectsByIdsForUser]); this
     * read is a plain existence check.
     *
     * @param id The unique identifier of the project.
     * @return Either [ProjectError.NotFound] if not found, or the [ProjectEntity].
     */
    suspend fun getProjectById(id: Long): Either<ProjectError.NotFound, ProjectEntity>

    /**
     * Loads the requested projects that are owned by [userId], preserving the order of [ids].
     * Missing or foreign projects are omitted so callers can use the result as an ownership check
     * (a missing/foreign id collapses to the same not-found outcome).
     *
     * @param userId User whose ownership is required.
     * @param ids Project ids to resolve.
     * @return Owned project entities in requested order.
     */
    suspend fun getProjectsByIdsForUser(userId: Long, ids: List<Long>): List<ProjectEntity>

    /**
     * Whether the user already owns a project with the given name.
     *
     * Used by the service layer to enforce per-owner project-name uniqueness.
     *
     * @param userId ID of the owner user.
     * @param name The project name to check.
     * @return `true` if the user owns a project with that name, `false` otherwise.
     */
    suspend fun projectNameExistsForUser(userId: Long, name: String): Boolean

    /**
     * Creates a new project row.
     *
     * Name uniqueness is NOT enforced here (the column is not unique); the caller is responsible for
     * checking [projectNameExistsForUser] first. Technical persistence failures propagate as
     * exceptions. The caller must insert the ownership link via [ProjectOwnershipDao.setOwner]
     * (atomically, in the same transaction).
     *
     * @param name Machine-readable project name (unique per owner; checked by the caller).
     * @param description Free-form description of the project.
     * @return The newly created [ProjectEntity].
     */
    suspend fun insertProject(name: String, description: String): ProjectEntity

    /**
     * Updates an existing project row (a full replacement of `name` and `description`).
     *
     * @param project The [ProjectEntity] with updated values. The ID must match an existing project.
     * @return Either [ProjectError.NotFound] if the project does not exist, or Unit on success.
     */
    suspend fun updateProject(project: ProjectEntity): Either<ProjectError.NotFound, Unit>

    /**
     * Deletes a project row by ID.
     *
     * `project_owners` row cascades and the member roles' `project_id` is nulled; `chat_sessions.project_id`
     * becomes null (`ON DELETE SET NULL`). The service captures the affected sessions and clears
     * their roles in the same transaction before calling this.
     *
     * @param id The unique identifier of the project to delete.
     * @return Either [ProjectError.NotFound] if not found, or Unit on success.
     */
    suspend fun deleteProject(id: Long): Either<ProjectError.NotFound, Unit>
}