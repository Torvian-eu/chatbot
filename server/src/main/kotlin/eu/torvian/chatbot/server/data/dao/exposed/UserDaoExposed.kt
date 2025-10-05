package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.data.tables.mappers.toUserEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.datetime.Instant
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [UserDao].
 */
class UserDaoExposed(
    private val transactionScope: TransactionScope
) : UserDao {

    override suspend fun getAllUsers(): List<UserEntity> =
        transactionScope.transaction {
            UsersTable.selectAll()
                .map { it.toUserEntity() }
        }

    override suspend fun getUserById(id: Long): Either<UserError.UserNotFound, UserEntity> =
        transactionScope.transaction {
            UsersTable.selectAll().where { UsersTable.id eq id }
                .singleOrNull()
                ?.toUserEntity()
                ?.right()
                ?: UserError.UserNotFound(id).left()
        }

    override suspend fun getUserByUsername(username: String): Either<UserError.UserNotFoundByUsername, UserEntity> =
        transactionScope.transaction {
            UsersTable.selectAll().where { UsersTable.username eq username }
                .singleOrNull()
                ?.toUserEntity()
                ?.right()
                ?: UserError.UserNotFoundByUsername(username).left()
        }

    override suspend fun insertUser(
        username: String,
        passwordHash: String,
        email: String?,
        status: UserStatus,
        requiresPasswordChange: Boolean
    ): Either<UserError, UserEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = UsersTable.insert {
                        it[UsersTable.username] = username
                        it[UsersTable.passwordHash] = passwordHash
                        it[UsersTable.email] = email
                        it[UsersTable.status] = status
                        it[UsersTable.createdAt] = System.currentTimeMillis()
                        it[UsersTable.updatedAt] = System.currentTimeMillis()
                        it[UsersTable.requiresPasswordChange] = requiresPasswordChange
                    }

                    val newId = insertStatement[UsersTable.id].value
                    getUserById(newId).bind()
                }) { e: ExposedSQLException ->
                    when {
                        e.isUniqueConstraintViolation() -> {
                            // Determine which constraint was violated
                            val message = e.message ?: e.cause?.message ?: ""
                            when {
                                message.contains("username", ignoreCase = true) ->
                                    raise(UserError.UsernameAlreadyExists(username))

                                message.contains("email", ignoreCase = true) ->
                                    raise(UserError.EmailAlreadyExists(email ?: ""))

                                else -> raise(UserError.UsernameAlreadyExists(username)) // Default assumption
                            }
                        }

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun updateUser(user: UserEntity): Either<UserError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = UsersTable.update({ UsersTable.id eq user.id }) {
                        it[username] = user.username
                        it[passwordHash] = user.passwordHash
                        it[email] = user.email
                        it[status] = user.status
                        it[updatedAt] = System.currentTimeMillis()
                        it[lastLogin] = user.lastLogin?.toEpochMilliseconds()
                        it[requiresPasswordChange] = user.requiresPasswordChange
                    }
                    ensure(updatedRowCount != 0) { UserError.UserNotFound(user.id) }
                }) { e: ExposedSQLException ->
                    when {
                        e.isUniqueConstraintViolation() -> {
                            val message = e.message ?: e.cause?.message ?: ""
                            when {
                                message.contains("username", ignoreCase = true) ->
                                    raise(UserError.UsernameAlreadyExists(user.username))

                                message.contains("email", ignoreCase = true) ->
                                    raise(UserError.EmailAlreadyExists(user.email ?: ""))

                                else -> raise(UserError.UsernameAlreadyExists(user.username))
                            }
                        }

                        else -> throw e
                    }
                }
            }
        }

    override suspend fun updateLastLogin(id: Long, lastLogin: Long): Either<UserError.UserNotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = UsersTable.update({ UsersTable.id eq id }) {
                    it[UsersTable.lastLogin] = lastLogin
                    it[updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { UserError.UserNotFound(id) }
            }
        }

    override suspend fun deleteUser(id: Long): Either<UserError.UserNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = UsersTable.deleteWhere { UsersTable.id eq id }
                ensure(deletedCount != 0) { UserError.UserNotFound(id) }
            }
        }

    // --- New methods ---

    override suspend fun getAllUsersWithDetails(): List<UserWithDetails> =
        transactionScope.transaction {
            // Build a single query that fetches users with optional role and group data
            val query = UsersTable
                .leftJoin(UserRoleAssignmentsTable, { UsersTable.id }, { UserRoleAssignmentsTable.userId })
                .leftJoin(RolesTable, { UserRoleAssignmentsTable.roleId }, { RolesTable.id })
                .leftJoin(UserGroupMembershipsTable, { UsersTable.id }, { UserGroupMembershipsTable.userId })
                .leftJoin(UserGroupsTable, { UserGroupMembershipsTable.groupId }, { UserGroupsTable.id })
                .selectAll()

            val rows = query.toList()
            if (rows.isEmpty()) return@transaction emptyList()

            // Group the flat, duplicated rows by user id and map each group to a single UserWithDetails
            rows.groupBy { it[UsersTable.id].value }
                .map { (userId, userRows) ->
                    val first = userRows.first()

                    // Extract roles from the user's rows
                    val roles = userRows.mapNotNull { row ->
                        row.getOrNull(RolesTable.id)?.let { roleId ->
                            Role(
                                id = roleId.value,
                                name = row[RolesTable.name],
                                description = row[RolesTable.description]
                            )
                        }
                    }.distinctBy { it.id }

                    // Extract groups from the user's rows
                    val groups = userRows.mapNotNull { row ->
                        row.getOrNull(UserGroupsTable.id)?.let { groupId ->
                            UserGroup(
                                id = groupId.value,
                                name = row[UserGroupsTable.name],
                                description = row[UserGroupsTable.description]
                            )
                        }
                    }.distinctBy { it.id }

                    // Create UserWithDetails
                    UserWithDetails(
                        id = userId,
                        username = first[UsersTable.username],
                        email = first[UsersTable.email],
                        status = first[UsersTable.status],
                        roles = roles,
                        userGroups = groups,
                        createdAt = Instant.fromEpochMilliseconds(first[UsersTable.createdAt]),
                        lastLogin = first[UsersTable.lastLogin]?.let { Instant.fromEpochMilliseconds(it) },
                        requiresPasswordChange = first[UsersTable.requiresPasswordChange]
                    )
                }
        }

    override suspend fun getUserByIdWithDetails(id: Long): Either<UserError.UserNotFound, UserWithDetails> =
        transactionScope.transaction {
            val rows = UsersTable
                .leftJoin(UserRoleAssignmentsTable, { UsersTable.id }, { UserRoleAssignmentsTable.userId })
                .leftJoin(RolesTable, { UserRoleAssignmentsTable.roleId }, { RolesTable.id })
                .leftJoin(UserGroupMembershipsTable, { UsersTable.id }, { UserGroupMembershipsTable.userId })
                .leftJoin(UserGroupsTable, { UserGroupMembershipsTable.groupId }, { UserGroupsTable.id })
                .selectAll()
                .where { UsersTable.id eq id }
                .toList()

            if (rows.isEmpty()) return@transaction UserError.UserNotFound(id).left()

            val first = rows.first()

            // Extract roles and groups from the user's rows
            val roles = rows.mapNotNull { row ->
                row.getOrNull(RolesTable.id)?.let { roleId ->
                    Role(roleId.value, row[RolesTable.name], row[RolesTable.description])
                }
            }.distinctBy { it.id }

            // Extract groups from the user's rows
            val groups = rows.mapNotNull { row ->
                row.getOrNull(UserGroupsTable.id)?.let { groupId ->
                    UserGroup(groupId.value, row[UserGroupsTable.name], row[UserGroupsTable.description])
                }
            }.distinctBy { it.id }

            UserWithDetails(
                id = first[UsersTable.id].value,
                username = first[UsersTable.username],
                email = first[UsersTable.email],
                status = first[UsersTable.status],
                roles = roles,
                userGroups = groups,
                createdAt = Instant.fromEpochMilliseconds(first[UsersTable.createdAt]),
                lastLogin = first[UsersTable.lastLogin]?.let { Instant.fromEpochMilliseconds(it) },
                requiresPasswordChange = first[UsersTable.requiresPasswordChange]
            ).right()
        }

    override suspend fun updateUserStatus(id: Long, status: UserStatus): Either<UserError.UserNotFound, User> =
        transactionScope.transaction {
            either {
                val updatedRowCount = UsersTable.update({ UsersTable.id eq id }) {
                    it[UsersTable.status] = status
                    it[updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { UserError.UserNotFound(id) }

                // Return updated public view
                getUserById(id).bind().toUser()
            }
        }

    override suspend fun updatePasswordChangeRequired(id: Long, requiresPasswordChange: Boolean): Either<UserError.UserNotFound, User> =
        transactionScope.transaction {
            either {
                val updatedRowCount = UsersTable.update({ UsersTable.id eq id }) {
                    it[UsersTable.requiresPasswordChange] = requiresPasswordChange
                    it[updatedAt] = System.currentTimeMillis()
                }
                ensure(updatedRowCount != 0) { UserError.UserNotFound(id) }

                // Return updated public view
                getUserById(id).bind().toUser()
            }
        }
}
