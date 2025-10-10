package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.data.entities.mappers.toUserGroup
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.error.usergroup.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import eu.torvian.chatbot.server.data.dao.error.usergroup.AddUserToGroupError as DaoAddUserToGroupError
import eu.torvian.chatbot.server.data.dao.error.usergroup.DeleteGroupError as DaoDeleteGroupError
import eu.torvian.chatbot.server.data.dao.error.usergroup.GetGroupByIdError as DaoGetGroupByIdError
import eu.torvian.chatbot.server.data.dao.error.usergroup.GetGroupByNameError as DaoGetGroupByNameError
import eu.torvian.chatbot.server.data.dao.error.usergroup.InsertGroupError as DaoInsertGroupError
import eu.torvian.chatbot.server.data.dao.error.usergroup.RemoveUserFromGroupError as DaoRemoveUserFromGroupError
import eu.torvian.chatbot.server.data.dao.error.usergroup.UpdateGroupError as DaoUpdateGroupError

/**
 * Implementation of [UserGroupService] providing user group management with business logic protection.
 *
 * This implementation ensures that special groups (e.g., "All Users") are protected from
 * deletion and certain modifications. It uses Arrow's Raise pattern for clean error handling
 * and transaction scopes for data consistency.
 */
class UserGroupServiceImpl(
    private val userGroupDao: UserGroupDao,
    private val userDao: UserDao,
    private val transactionScope: TransactionScope
) : UserGroupService {

    companion object {
        private val logger: Logger = LogManager.getLogger(UserGroupServiceImpl::class.java)

        /**
         * Maximum allowed length for group names.
         */
        private const val MAX_GROUP_NAME_LENGTH = 100
    }

    override suspend fun getAllGroups(): List<UserGroup> =
        transactionScope.transaction {
            logger.debug("Retrieving all user groups")

            userGroupDao.getAllGroups().map { it.toUserGroup() }
        }

    override suspend fun getGroupById(groupId: Long): Either<GetGroupByIdError, UserGroup> =
        transactionScope.transaction {
            either {
                logger.debug("Retrieving user group by ID: $groupId")

                val groupEntity = withError({ error: DaoGetGroupByIdError ->
                    when (error) {
                        is DaoGetGroupByIdError.GroupNotFound -> GetGroupByIdError.NotFound(error.id)
                    }
                }) {
                    userGroupDao.getGroupById(groupId).bind()
                }

                groupEntity.toUserGroup()
            }
        }

    override suspend fun getGroupByName(name: String): Either<GetGroupByNameError, UserGroup> =
        transactionScope.transaction {
            either {
                logger.debug("Retrieving user group by name: $name")

                val groupEntity = withError({ error: DaoGetGroupByNameError ->
                    when (error) {
                        is DaoGetGroupByNameError.GroupNotFound -> GetGroupByNameError.NotFound(error.name)
                    }
                }) {
                    userGroupDao.getGroupByName(name).bind()
                }

                groupEntity.toUserGroup()
            }
        }

    override suspend fun createGroup(
        name: String,
        description: String?
    ): Either<CreateGroupError, UserGroup> = transactionScope.transaction {
        either {
            logger.info("Creating new user group: $name")

            // Validate group name
            ensure(name.isNotBlank()) {
                CreateGroupError.InvalidGroupName(name, "Group name cannot be blank")
            }

            ensure(name.length <= MAX_GROUP_NAME_LENGTH) {
                CreateGroupError.InvalidGroupName(
                    name,
                    "Group name cannot exceed $MAX_GROUP_NAME_LENGTH characters"
                )
            }

            // Attempt to insert the group
            val groupEntity = withError({ error: DaoInsertGroupError ->
                when (error) {
                    is DaoInsertGroupError.GroupNameAlreadyExists -> CreateGroupError.GroupNameAlreadyExists(name)
                }
            }) {
                userGroupDao.insertGroup(name, description).bind()
            }

            logger.info("Successfully created user group: $name with ID: ${groupEntity.id}")
            groupEntity.toUserGroup()
        }
    }

    override suspend fun updateGroup(
        groupId: Long,
        name: String,
        description: String?
    ): Either<UpdateGroupError, Unit> = transactionScope.transaction {
        either {
            logger.info("Updating user group ID: $groupId")

            // Validate name if provided
            ensure(name.isNotBlank()) {
                UpdateGroupError.InvalidOperation("Group name cannot be blank")
            }

            ensure(name.length <= MAX_GROUP_NAME_LENGTH) {
                UpdateGroupError.InvalidOperation(
                    "Group name cannot exceed $MAX_GROUP_NAME_LENGTH characters"
                )
            }

            // Get existing group
            val existingGroup = withError({ error: DaoGetGroupByIdError ->
                when (error) {
                    is DaoGetGroupByIdError.GroupNotFound -> UpdateGroupError.NotFound(groupId)
                }
            }) {
                userGroupDao.getGroupById(groupId).bind()
            }

            // Protect "All Users" group from being renamed
            ensure(
                !(existingGroup.name == CommonUserGroups.ALL_USERS &&
                        name != CommonUserGroups.ALL_USERS)
            ) { UpdateGroupError.InvalidOperation("Cannot rename the All Users group") }

            // Create updated group entity
            val updatedGroup = existingGroup.copy(
                name = name,
                description = description
            )

            // Only update if there are actual changes
            if (updatedGroup != existingGroup) {
                withError({ error: DaoUpdateGroupError ->
                    when (error) {
                        is DaoUpdateGroupError.GroupNotFound ->
                            UpdateGroupError.NotFound(groupId)

                        is DaoUpdateGroupError.GroupNameAlreadyExists ->
                            UpdateGroupError.GroupNameAlreadyExists(name)
                    }
                }) {
                    userGroupDao.updateGroup(updatedGroup).bind()
                }

                logger.info("Successfully updated user group ID: $groupId")
            } else {
                logger.debug("No changes detected for user group ID: $groupId")
            }
        }
    }

    override suspend fun deleteGroup(groupId: Long): Either<DeleteGroupError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Deleting user group ID: $groupId")

                // Get existing group
                val existingGroup = withError({ error: DaoGetGroupByIdError ->
                    when (error) {
                        is DaoGetGroupByIdError.GroupNotFound -> DeleteGroupError.NotFound(groupId)
                    }
                }) {
                    userGroupDao.getGroupById(groupId).bind()
                }

                // Protect "All Users" group from deletion
                ensure(existingGroup.name != CommonUserGroups.ALL_USERS) {
                    DeleteGroupError.InvalidOperation("Cannot delete the All Users group")
                }

                // Delete the group
                withError({ error: DaoDeleteGroupError ->
                    when (error) {
                        is DaoDeleteGroupError.GroupNotFound -> DeleteGroupError.NotFound(groupId)
                    }
                }) {
                    userGroupDao.deleteGroup(groupId).bind()
                }

                logger.info("Successfully deleted user group ID: $groupId")
            }
        }

    override suspend fun getAllUsersGroup(): Either<GetGroupByNameError, UserGroup> =
        getGroupByName(CommonUserGroups.ALL_USERS)


    override suspend fun addUserToGroup(userId: Long, groupId: Long): Either<AddUserToGroupError, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("Adding user $userId to group $groupId")

                withError({ error: DaoAddUserToGroupError ->
                    when (error) {
                        is DaoAddUserToGroupError.MembershipAlreadyExists ->
                            AddUserToGroupError.AlreadyMember(userId, groupId)

                        is DaoAddUserToGroupError.ForeignKeyViolation ->
                            AddUserToGroupError.InvalidRelatedEntity(error.details)
                    }
                }) {
                    userGroupDao.addUserToGroup(userId, groupId).bind()
                }

                logger.info("Successfully added user $userId to group $groupId")
            }
        }

    override suspend fun removeUserFromGroup(
        userId: Long,
        groupId: Long
    ): Either<RemoveUserFromGroupError, Unit> = transactionScope.transaction {
        either {
            logger.info("Removing user $userId from group $groupId")

            // Get the group to check if it's protected
            val group = withError({ error: DaoGetGroupByIdError ->
                when (error) {
                    is DaoGetGroupByIdError.GroupNotFound -> RemoveUserFromGroupError.GroupNotFound(groupId)
                }
            }) {
                userGroupDao.getGroupById(groupId).bind()
            }

            // Protect "All Users" group from user removal
            ensure(group.name != CommonUserGroups.ALL_USERS) {
                RemoveUserFromGroupError.InvalidOperation(
                    "Cannot remove users from the All Users group"
                )
            }

            // Remove the user from the group
            withError({ error: DaoRemoveUserFromGroupError ->
                when (error) {
                    is DaoRemoveUserFromGroupError.MembershipNotFound ->
                        RemoveUserFromGroupError.NotFound(userId, groupId)
                }
            }) {
                userGroupDao.removeUserFromGroup(userId, groupId).bind()
            }

            logger.info("Successfully removed user $userId from group $groupId")
        }
    }

    override suspend fun getUsersInGroup(groupId: Long): List<User> {
        val userIds = userGroupDao.getUsersInGroup(groupId)
        return userIds.mapNotNull { userId ->
            userDao.getUserById(userId).getOrNull()?.toUser()
        }
    }
}
