package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.*
import arrow.core.raise.*
import eu.torvian.chatbot.server.data.dao.ApiSecretDao
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError.SecretAlreadyExists
import eu.torvian.chatbot.server.data.dao.error.ApiSecretError.SecretNotFound
import eu.torvian.chatbot.server.data.tables.ApiSecretTable
import eu.torvian.chatbot.server.domain.security.EncryptedSecret
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/**
 * Exposed ORM implementation of [ApiSecretDao].
 *
 * Manages encrypted API secrets in the `api_secrets` database table using Exposed.
 *
 * @property transactionScope The transaction scope for database access within coroutines.
 */
class ApiSecretDaoExposed(
    private val transactionScope: TransactionScope
) : ApiSecretDao {

    override suspend fun saveSecret(
        alias: String,
        encryptedSecret: EncryptedSecret
    ): Either<SecretAlreadyExists, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    // Insert new secret
                    val now = System.currentTimeMillis()
                    val insertStatement = ApiSecretTable.insert {
                        it[ApiSecretTable.alias] = alias
                        it[encrypted_credential] = encryptedSecret.encryptedSecret
                        it[wrapped_dek] = encryptedSecret.encryptedDEK
                        it[key_version] = encryptedSecret.keyVersion
                        it[created_at] = now
                        it[updated_at] = now
                    }
                    if (insertStatement.insertedCount == 0) {
                        throw IllegalStateException("Failed to insert new secret")
                    }
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) { SecretAlreadyExists(alias) }
                    throw e
                }
            }
        }

    override suspend fun getSecret(alias: String): Either<SecretNotFound, EncryptedSecret> =
        transactionScope.transaction {
            either {
                ApiSecretTable
                    .selectAll().where { ApiSecretTable.alias eq alias }
                    .singleOrNull()
                    ?.let {
                        EncryptedSecret(
                            encryptedSecret = it[ApiSecretTable.encrypted_credential],
                            encryptedDEK = it[ApiSecretTable.wrapped_dek],
                            keyVersion = it[ApiSecretTable.key_version]
                        )
                    }
                    ?: raise(SecretNotFound(alias))
            }
        }

    override suspend fun deleteSecret(alias: String): Either<SecretNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ApiSecretTable.deleteWhere { ApiSecretTable.alias eq alias }
                ensure(deletedCount != 0) { SecretNotFound(alias) }
            }
        }
}
