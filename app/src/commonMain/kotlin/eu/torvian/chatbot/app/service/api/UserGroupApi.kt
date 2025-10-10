package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.api.admin.AddUserToGroupRequest
import eu.torvian.chatbot.common.models.api.admin.CreateUserGroupRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserGroupRequest

/**
 * API client interface for user group management operations.
 *
 * This interface provides methods to interact with the user group management endpoints
 * on the server, handling CRUD operations for user groups and group membership management
 * with proper error handling using Arrow's Either type.
 *
 * All operations require the MANAGE_USER_GROUPS permission on the backend.
 */
interface UserGroupApi {
    /**
     * Retrieves all user groups from the server.
     *
     * Corresponds to `GET /api/v1/user-groups`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * @return Either [ApiResourceError] if request fails, or List of [UserGroup] objects
     */
    suspend fun getAllGroups(): Either<ApiResourceError, List<UserGroup>>

    /**
     * Retrieves a specific user group by its unique ID.
     *
     * Corresponds to `GET /api/v1/user-groups/{groupId}`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * @param groupId The unique identifier of the group to retrieve
     * @return Either [ApiResourceError] if request fails, or the [UserGroup] object
     */
    suspend fun getGroupById(groupId: Long): Either<ApiResourceError, UserGroup>

    /**
     * Creates a new user group with the specified details.
     *
     * Corresponds to `POST /api/v1/user-groups`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * @param request The group creation request containing name and description
     * @return Either [ApiResourceError] if request fails, or the newly created [UserGroup]
     */
    suspend fun createGroup(request: CreateUserGroupRequest): Either<ApiResourceError, UserGroup>

    /**
     * Updates an existing user group with new details.
     *
     * Corresponds to `PUT /api/v1/user-groups/{groupId}`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * @param groupId The unique identifier of the group to update
     * @param request The group update request containing new name and description
     * @return Either [ApiResourceError] if request fails, or Unit on success
     */
    suspend fun updateGroup(groupId: Long, request: UpdateUserGroupRequest): Either<ApiResourceError, Unit>

    /**
     * Deletes a user group by its unique ID.
     *
     * Corresponds to `DELETE /api/v1/user-groups/{groupId}`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * Note: Special groups like "All Users" cannot be deleted.
     *
     * @param groupId The unique identifier of the group to delete
     * @return Either [ApiResourceError] if request fails, or Unit on success
     */
    suspend fun deleteGroup(groupId: Long): Either<ApiResourceError, Unit>

    /**
     * Retrieves all users that belong to a specific group.
     *
     * Corresponds to `GET /api/v1/user-groups/{groupId}/members`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * @param groupId The unique identifier of the group
     * @return Either [ApiResourceError] if request fails, or List of [User] objects
     */
    suspend fun getGroupMembers(groupId: Long): Either<ApiResourceError, List<User>>

    /**
     * Adds a user to a group.
     *
     * Corresponds to `POST /api/v1/user-groups/{groupId}/members`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * @param groupId The unique identifier of the group
     * @param request The request containing the user ID to add
     * @return Either [ApiResourceError] if request fails, or Unit on success
     */
    suspend fun addUserToGroup(groupId: Long, request: AddUserToGroupRequest): Either<ApiResourceError, Unit>

    /**
     * Removes a user from a group.
     *
     * Corresponds to `DELETE /api/v1/user-groups/{groupId}/members/{userId}`.
     * Requires permission: MANAGE_USER_GROUPS
     *
     * Note: Users cannot be removed from special groups like "All Users".
     *
     * @param groupId The unique identifier of the group
     * @param userId The unique identifier of the user to remove
     * @return Either [ApiResourceError] if request fails, or Unit on success
     */
    suspend fun removeUserFromGroup(groupId: Long, userId: Long): Either<ApiResourceError, Unit>
}

