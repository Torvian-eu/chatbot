package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.api.admin.AddUserToGroupRequest
import eu.torvian.chatbot.common.models.api.admin.CreateUserGroupRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserGroupRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing user groups.
 *
 * This repository serves as the single source of truth for user group data in the application,
 * providing reactive data streams through StateFlow and handling all group-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository maintains an internal cache of group data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 */
interface UserGroupRepository {
    /**
     * Reactive stream of all user groups in the system.
     *
     * This StateFlow provides real-time updates whenever group data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all groups wrapped in DataState
     */
    val groups: StateFlow<DataState<RepositoryError, List<UserGroup>>>

    /**
     * Loads all user groups from the backend.
     *
     * This operation fetches the latest group data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadGroups(): Either<RepositoryError, Unit>

    /**
     * Retrieves a specific user group by its unique ID.
     *
     * This operation fetches the group from the backend but does not update the internal StateFlow.
     *
     * @param groupId The unique identifier of the group to retrieve
     * @return Either.Right with the UserGroup on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getGroupById(groupId: Long): Either<RepositoryError, UserGroup>

    /**
     * Creates a new user group with the specified details.
     *
     * Upon successful creation, the new group is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param request The group creation request containing name and description
     * @return Either.Right with the created UserGroup on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createGroup(request: CreateUserGroupRequest): Either<RepositoryError, UserGroup>

    /**
     * Updates an existing user group with new details.
     *
     * Upon successful update, the modified group replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param groupId The unique identifier of the group to update
     * @param request The group update request containing new name and description
     * @return Either.Right with Unit on success, or Either.Left with RepositoryError on failure
     */
    suspend fun updateGroup(groupId: Long, request: UpdateUserGroupRequest): Either<RepositoryError, Unit>

    /**
     * Deletes a user group by its unique ID.
     *
     * Upon successful deletion, the group is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * Note: Special groups like "All Users" cannot be deleted.
     *
     * @param groupId The unique identifier of the group to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteGroup(groupId: Long): Either<RepositoryError, Unit>

    /**
     * Retrieves all users that belong to a specific group.
     *
     * This operation fetches the members from the backend but does not update the internal StateFlow.
     *
     * @param groupId The unique identifier of the group
     * @return Either.Right with List of User objects on success, or Either.Left with RepositoryError on failure
     */
    suspend fun getGroupMembers(groupId: Long): Either<RepositoryError, List<User>>

    /**
     * Adds a user to a group.
     *
     * This operation does not update the internal groups StateFlow, but consumers can
     * call getGroupMembers to refresh the member list.
     *
     * @param groupId The unique identifier of the group
     * @param request The request containing the user ID to add
     * @return Either.Right with Unit on success, or Either.Left with RepositoryError on failure
     */
    suspend fun addUserToGroup(groupId: Long, request: AddUserToGroupRequest): Either<RepositoryError, Unit>

    /**
     * Removes a user from a group.
     *
     * This operation does not update the internal groups StateFlow, but consumers can
     * call getGroupMembers to refresh the member list.
     *
     * Note: Users cannot be removed from special groups like "All Users".
     *
     * @param groupId The unique identifier of the group
     * @param userId The unique identifier of the user to remove
     * @return Either.Right with Unit on success, or Either.Left with RepositoryError on failure
     */
    suspend fun removeUserFromGroup(groupId: Long, userId: Long): Either<RepositoryError, Unit>
}

