package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.server.service.core.error.usergroup.*

/**
 * Service interface for user group management operations.
 *
 * This service provides high-level operations for managing user-defined groups
 * used for resource sharing and access control. It handles business logic such as
 * protecting special groups (e.g., "All Users") from modification and ensuring
 * group names are unique.
 *
 * Unlike [GroupService] which manages chat conversation groups, this service
 * manages user access control groups.
 */
interface UserGroupService {
    /**
     * Retrieves all user groups in the system.
     *
     * @return List of all [UserGroup] objects; empty list if no groups exist
     */
    suspend fun getAllGroups(): List<UserGroup>

    /**
     * Retrieves a user group by its unique ID.
     *
     * @param groupId The unique identifier of the group
     * @return Either [GetGroupByIdError] if not found, or the [UserGroup]
     */
    suspend fun getGroupById(groupId: Long): Either<GetGroupByIdError, UserGroup>

    /**
     * Retrieves a user group by its unique name.
     *
     * @param name The name of the group to search for
     * @return Either [GetGroupByNameError] if not found, or the [UserGroup]
     */
    suspend fun getGroupByName(name: String): Either<GetGroupByNameError, UserGroup>

    /**
     * Creates a new user group with the specified name and description.
     *
     * This method validates the group name and ensures it doesn't conflict with
     * existing groups.
     *
     * @param name Unique name for the group (case-sensitive)
     * @param description Optional description explaining the group's purpose
     * @return Either [CreateGroupError] if creation fails, or the newly created [UserGroup]
     */
    suspend fun createGroup(
        name: String,
        description: String?
    ): Either<CreateGroupError, UserGroup>

    /**
     * Updates an existing user group's name and/or description.
     *
     * Special groups (e.g., "All Users") are protected from certain modifications.
     * If either parameter is null, the corresponding field is not updated.
     *
     * @param groupId The unique identifier of the group to update
     * @param name The new name for the group
     * @param description The new description for the group
     * @return Either [UpdateGroupError] if update fails, or Unit on success
     */
    suspend fun updateGroup(
        groupId: Long,
        name: String,
        description: String?
    ): Either<UpdateGroupError, Unit>

    /**
     * Deletes a user group by its unique ID.
     *
     * Special groups (e.g., "All Users") cannot be deleted. Groups may also be
     * protected if they are referenced by other entities.
     *
     * @param groupId The unique identifier of the group to delete
     * @return Either [DeleteGroupError] if deletion fails, or Unit on success
     */
    suspend fun deleteGroup(groupId: Long): Either<DeleteGroupError, Unit>

    /**
     * Retrieves the special "All Users" group.
     *
     * This is a convenience method for getting the system-wide group that all users
     * belong to. Resources shared with this group are effectively public.
     *
     * @return Either [GetGroupByNameError] if the group doesn't exist, or the [UserGroup]
     */
    suspend fun getAllUsersGroup(): Either<GetGroupByNameError, UserGroup>

    /**
     * Adds a user to a group.
     *
     * @param userId The unique identifier of the user
     * @param groupId The unique identifier of the group
     * @return Either [AddUserToGroupError] if the operation fails, or Unit on success
     */
    suspend fun addUserToGroup(userId: Long, groupId: Long): Either<AddUserToGroupError, Unit>

    /**
     * Removes a user from a group.
     *
     * Special groups (e.g., "All Users") may be protected from user removal.
     *
     * @param userId The unique identifier of the user
     * @param groupId The unique identifier of the group
     * @return Either [RemoveUserFromGroupError] if the operation fails, or Unit on success
     */
    suspend fun removeUserFromGroup(userId: Long, groupId: Long): Either<RemoveUserFromGroupError, Unit>
}

