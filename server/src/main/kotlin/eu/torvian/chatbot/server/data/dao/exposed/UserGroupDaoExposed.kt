package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.dao.error.usergroup.*
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import eu.torvian.chatbot.server.data.tables.UserGroupMembershipsTable
import eu.torvian.chatbot.server.data.tables.UserGroupsTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserGroupEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [UserGroupDao].
 */
class UserGroupDaoExposed(
    private val transactionScope: TransactionScope
) : UserGroupDao {

    override suspend fun getAllGroups(): List<UserGroupEntity> =
        transactionScope.transaction {
            UserGroupsTable.selectAll()
                .map { it.toUserGroupEntity() }
        }

    override suspend fun getGroupById(id: Long): Either<GroupByIdError, UserGroupEntity> =
        transactionScope.transaction {
            UserGroupsTable.selectAll().where { UserGroupsTable.id eq id }
                .singleOrNull()
                ?.toUserGroupEntity()
                ?.right()
                ?: GroupByIdError.GroupNotFound(id).left()
        }

    override suspend fun getGroupByName(name: String): Either<GroupByNameError, UserGroupEntity> =
        transactionScope.transaction {
            UserGroupsTable.selectAll().where { UserGroupsTable.name eq name }
                .singleOrNull()
                ?.toUserGroupEntity()
                ?.right()
                ?: GroupByNameError.GroupNotFoundByName(name).left()
        }

    override suspend fun insertGroup(
        name: String,
        description: String?
    ): Either<InsertGroupError, UserGroupEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = UserGroupsTable.insert {
                        it[UserGroupsTable.name] = name
                        it[UserGroupsTable.description] = description
                    }

                    val newId = insertStatement[UserGroupsTable.id].value
                    UserGroupEntity(
                        id = newId,
                        name = name,
                        description = description
                    )
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) { InsertGroupError.GroupNameAlreadyExists(name) }
                    throw e
                }
            }
        }

    override suspend fun updateGroup(group: UserGroupEntity): Either<UpdateGroupError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = UserGroupsTable.update({ UserGroupsTable.id eq group.id }) {
                        it[name] = group.name
                        it[description] = group.description
                    }
                    ensure(updatedRowCount != 0) { UpdateGroupError.GroupNotFound(group.id) }
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) { UpdateGroupError.GroupNameAlreadyExists(group.name) }
                    throw e
                }
            }
        }

    override suspend fun deleteGroup(id: Long): Either<DeleteGroupError, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = UserGroupsTable.deleteWhere { UserGroupsTable.id eq id }
                ensure(deletedCount != 0) { DeleteGroupError.GroupNotFound(id) }
            }
        }

    override suspend fun getGroupsForUser(userId: Long): List<UserGroupEntity> =
        transactionScope.transaction {
            UserGroupsTable
                .join(
                    UserGroupMembershipsTable,
                    JoinType.INNER,
                    additionalConstraint = { UserGroupsTable.id eq UserGroupMembershipsTable.groupId }
                )
                .selectAll()
                .where { UserGroupMembershipsTable.userId eq userId }
                .orderBy(UserGroupsTable.name to SortOrder.ASC)
                .map { it.toUserGroupEntity() }
        }

    override suspend fun getUsersInGroup(groupId: Long): List<Long> =
        transactionScope.transaction {
            UserGroupMembershipsTable
                .selectAll()
                .where { UserGroupMembershipsTable.groupId eq groupId }
                .map { it[UserGroupMembershipsTable.userId].value }
        }

    override suspend fun addUserToGroup(userId: Long, groupId: Long): Either<AddUserToGroupError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    UserGroupMembershipsTable.insert {
                        it[UserGroupMembershipsTable.userId] = userId
                        it[UserGroupMembershipsTable.groupId] = groupId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isUniqueConstraintViolation() ->
                            raise(AddUserToGroupError.MembershipAlreadyExists(userId, groupId))

                        e.isForeignKeyViolation() ->
                            raise(AddUserToGroupError.ForeignKeyViolation("User ID $userId or Group ID $groupId does not exist"))

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun removeUserFromGroup(userId: Long, groupId: Long): Either<RemoveUserFromGroupError, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = UserGroupMembershipsTable.deleteWhere {
                    (UserGroupMembershipsTable.userId eq userId) and
                            (UserGroupMembershipsTable.groupId eq groupId)
                }
                ensure(deletedCount != 0) { RemoveUserFromGroupError.MembershipNotFound(userId, groupId) }
            }
        }

    override suspend fun isUserInGroup(userId: Long, groupId: Long): Boolean =
        transactionScope.transaction {
            UserGroupMembershipsTable
                .selectAll()
                .where {
                    (UserGroupMembershipsTable.userId eq userId) and
                            (UserGroupMembershipsTable.groupId eq groupId)
                }
                .singleOrNull() != null
        }
}
