package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.api.admin.CreateRoleRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateRoleRequest

/**
 * API client interface for role management operations.
 *
 * This interface provides methods to interact with the role management endpoints
 * on the server, handling CRUD operations for roles with proper error handling
 * using Arrow's Either type.
 */
interface RoleApi {
    /**
     * Retrieves all available roles from the server.
     *
     * @return Either [ApiResourceError] if request fails, or List of [Role] objects
     */
    suspend fun getAllRoles(): Either<ApiResourceError, List<Role>>

    /**
     * Retrieves a specific role by its unique ID.
     *
     * @param id The unique identifier of the role to retrieve
     * @return Either [ApiResourceError] if request fails, or the [Role] object
     */
    suspend fun getRoleById(id: Long): Either<ApiResourceError, Role>

    /**
     * Creates a new role with the specified details.
     *
     * @param request The role creation request containing name and description
     * @return Either [ApiResourceError] if request fails, or the newly created [Role]
     */
    suspend fun createRole(request: CreateRoleRequest): Either<ApiResourceError, Role>

    /**
     * Updates an existing role with new details.
     *
     * @param id The unique identifier of the role to update
     * @param request The role update request containing new name and description
     * @return Either [ApiResourceError] if request fails, or the updated [Role]
     */
    suspend fun updateRole(id: Long, request: UpdateRoleRequest): Either<ApiResourceError, Role>

    /**
     * Deletes a role by its unique ID.
     *
     * @param id The unique identifier of the role to delete
     * @return Either [ApiResourceError] if request fails, or Unit on success
     */
    suspend fun deleteRole(id: Long): Either<ApiResourceError, Unit>
}
