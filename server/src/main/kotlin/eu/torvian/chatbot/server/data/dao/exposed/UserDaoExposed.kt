package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.tables.UsersTable
import eu.torvian.chatbot.server.data.tables.mappers.toUserEntity
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
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
        email: String?
    ): Either<UserError, UserEntity> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = UsersTable.insert {
                        it[UsersTable.username] = username
                        it[UsersTable.passwordHash] = passwordHash
                        it[UsersTable.email] = email
                        it[UsersTable.createdAt] = System.currentTimeMillis()
                        it[UsersTable.updatedAt] = System.currentTimeMillis()
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
                        it[updatedAt] = System.currentTimeMillis()
                        it[lastLogin] = user.lastLogin?.toEpochMilliseconds()
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
}
