package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.usergroup.*
import eu.torvian.chatbot.server.data.entities.UserGroupEntity

/**
 * Data Access Object for managing user groups and group memberships.
 *
 * This DAO provides operations for managing user-defined groups and the relationships
 * between users and groups.
 */
interface UserGroupDao {
    /**
     * Retrieves all user groups in the system.
     *
     * @return List of all [UserGroupEntity] objects; empty list if no groups exist.
     */
    suspend fun getAllGroups(): List<UserGroupEntity>

    /**
     * Retrieves a user group by its unique ID.
     *
     * @param id The unique identifier of the group.
     * @return Either [GetGroupByIdError.GroupNotFound] if no group exists with the given ID,
     *         or the [UserGroupEntity] if found.
     */
    suspend fun getGroupById(id: Long): Either<GetGroupByIdError, UserGroupEntity>

    /**
     * Retrieves a user group by its unique name.
     *
     * @param name The name of the group to search for.
     * @return Either [GetGroupByNameError.GroupNotFound] if no group exists with the given name,
     *         or the [UserGroupEntity] if found.
     */
    suspend fun getGroupByName(name: String): Either<GetGroupByNameError, UserGroupEntity>

    /**
     * Creates a new user group.
     *
     * @param name Unique name for the new group.
     * @param description Optional description of the group's purpose.
     * @return Either [InsertGroupError.GroupNameAlreadyExists] if the name is already taken,
     *         or the newly created [UserGroupEntity] on success.
     */
    suspend fun insertGroup(
        name: String,
        description: String? = null
    ): Either<InsertGroupError, UserGroupEntity>

    /**
     * Updates an existing user group's information.
     *
     * @param group The [UserGroupEntity] with updated information.
     * @return Either [UpdateGroupError.GroupNotFound] if the group doesn't exist,
     *         [UpdateGroupError.GroupNameAlreadyExists] if the new name conflicts,
     *         or Unit on success.
     */
    suspend fun updateGroup(group: UserGroupEntity): Either<UpdateGroupError, Unit>

    /**
     * Deletes a user group by ID.
     *
     * @param id The unique identifier of the group to delete.
     * @return Either [DeleteGroupError.GroupNotFound] if the group doesn't exist, or Unit on success.
     */
    suspend fun deleteGroup(id: Long): Either<DeleteGroupError, Unit>

    /**
     * Retrieves all groups that a user belongs to.
     *
     * @param userId The unique identifier of the user.
     * @return List of [UserGroupEntity] objects the user belongs to; empty list if none.
     */
    suspend fun getGroupsForUser(userId: Long): List<UserGroupEntity>

    /**
     * Retrieves all user IDs that belong to a specific group.
     *
     * @param groupId The unique identifier of the group.
     * @return List of user IDs that belong to the group; empty list if none.
     */
    suspend fun getUsersInGroup(groupId: Long): List<Long>

    /**
     * Adds a user to a group.
     *
     * @param userId The unique identifier of the user.
     * @param groupId The unique identifier of the group.
     * @return Either [AddUserToGroupError.MembershipAlreadyExists] if the user is already in the group,
     *         [AddUserToGroupError.ForeignKeyViolation] if user or group doesn't exist,
     *         or Unit on success.
     */
    suspend fun addUserToGroup(userId: Long, groupId: Long): Either<AddUserToGroupError, Unit>

    /**
     * Removes a user from a group.
     *
     * @param userId The unique identifier of the user.
     * @param groupId The unique identifier of the group.
     * @return Either [RemoveUserFromGroupError.MembershipNotFound] if the user is not in the group or Unit on success.
     */
    suspend fun removeUserFromGroup(userId: Long, groupId: Long): Either<RemoveUserFromGroupError, Unit>

    /**
     * Checks if a user belongs to a specific group.
     *
     * @param userId The unique identifier of the user.
     * @param groupId The unique identifier of the group.
     * @return true if the user belongs to the group, false otherwise.
     */
    suspend fun isUserInGroup(userId: Long, groupId: Long): Boolean
}
