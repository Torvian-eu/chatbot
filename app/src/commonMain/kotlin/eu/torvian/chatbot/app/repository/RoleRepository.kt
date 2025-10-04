package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.Role
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for role management with reactive data streams.
 *
 * This repository provides role CRUD operations with StateFlow-based reactive updates,
 * allowing ViewModels to automatically react to role data changes. It follows the
 * same pattern as other repositories in the application for consistency.
 */
interface RoleRepository {
    /**
     * Reactive stream of all roles in the system.
     *
     * This StateFlow provides real-time updates whenever role data changes,
     * allowing ViewModels to automatically react to role changes without
     * manual polling or refresh operations.
     *
     * @return StateFlow containing the current state of role data loading
     */
    val roles: StateFlow<DataState<RepositoryError, List<Role>>>

    /**
     * Loads all roles from the server and updates the StateFlow.
     *
     * This method fetches the latest role data from the backend and updates
     * the reactive stream. It should be called when fresh data is needed.
     *
     * @return Either [RepositoryError] if loading fails, or Unit on success
     */
    suspend fun loadRoles(): Either<RepositoryError, Unit>

    /**
     * Loads details for a specific role from the backend.
     *
     * This operation fetches the latest role data and updates the role in the internal list.
     *
     * @param roleId The unique identifier of the role to load
     * @return Either.Right with the loaded Role on success, or Either.Left with RepositoryError on failure
     */
    suspend fun loadRoleDetails(roleId: Long): Either<RepositoryError, Role>

    /**
     * Creates a new role with the specified details.
     *
     * After successful creation, the role list will be automatically refreshed
     * to include the new role in the reactive stream.
     *
     * @param name Unique name for the new role (required, max 50 characters)
     * @param description Optional description explaining the role's purpose
     * @return Either [RepositoryError] if creation fails, or the newly created [Role]
     */
    suspend fun createRole(name: String, description: String?): Either<RepositoryError, Role>

    /**
     * Updates an existing role with new details.
     *
     * System roles (Admin, StandardUser) are protected from name changes.
     * After successful update, the role list will be automatically refreshed.
     *
     * @param id The unique identifier of the role to update
     * @param name The new name for the role (must be unique)
     * @param description The new description for the role
     * @return Either [RepositoryError] if update fails, or the updated [Role]
     */
    suspend fun updateRole(id: Long, name: String, description: String?): Either<RepositoryError, Role>

    /**
     * Deletes a role by its unique ID.
     *
     * System roles cannot be deleted, and roles assigned to users cannot be deleted.
     * After successful deletion, the role list will be automatically refreshed.
     *
     * @param id The unique identifier of the role to delete
     * @return Either [RepositoryError] if deletion fails, or Unit on success
     */
    suspend fun deleteRole(id: Long): Either<RepositoryError, Unit>
}
