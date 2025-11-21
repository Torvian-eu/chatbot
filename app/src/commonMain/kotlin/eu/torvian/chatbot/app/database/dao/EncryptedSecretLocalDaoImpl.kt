package eu.torvian.chatbot.app.database.dao

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.app.database.EncryptedSecretTableQueries
import eu.torvian.chatbot.app.database.dao.error.DeleteEncryptedSecretError
import eu.torvian.chatbot.app.database.dao.error.EncryptedSecretError
import eu.torvian.chatbot.app.database.dao.error.UpdateEncryptedSecretError
import eu.torvian.chatbot.app.database.model.EncryptedSecretEntity
import eu.torvian.chatbot.common.misc.transaction.TransactionScope

/**
 * Implementation of EncryptedSecretLocalDao using SQLDelight.
 *
 * This implementation delegates transaction management and context switching to the
 * provided [TransactionScope].
 *
 * @property queries The SQLDelight generated queries for the EncryptedSecretTable.
 * @property transactionScope The scope that manages database transactions.
 */
class EncryptedSecretLocalDaoImpl(
    private val queries: EncryptedSecretTableQueries,
    private val transactionScope: TransactionScope
) : EncryptedSecretLocalDao {

    override suspend fun insert(
        encryptedSecret: String,
        encryptedDEK: String,
        keyVersion: Int,
        createdAt: Long,
        updatedAt: Long
    ): EncryptedSecretEntity = transactionScope.transaction {
        queries.insert(
            encryptedSecret = encryptedSecret,
            encryptedDEK = encryptedDEK,
            keyVersion = keyVersion.toLong(),
            createdAt = createdAt,
            updatedAt = updatedAt
        ).let { rowsUpdated ->
            if (rowsUpdated != 1L) {
                throw IllegalStateException("Failed to insert encrypted secret")
            }
        }

        // Retrieve the last inserted row
        queries.lastInsertRow().executeAsOne().let { row ->
            EncryptedSecretEntity(
                id = row.id,
                encryptedSecret = row.encryptedSecret,
                encryptedDEK = row.encryptedDEK,
                keyVersion = row.keyVersion.toInt(),
                createdAt = row.createdAt,
                updatedAt = row.updatedAt
            )
        }
    }

    override suspend fun update(
        id: Long,
        encryptedSecret: String,
        encryptedDEK: String,
        keyVersion: Int,
        updatedAt: Long
    ): Either<UpdateEncryptedSecretError, Unit> = transactionScope.execute {
        either {
            queries.update(
                encryptedSecret = encryptedSecret,
                encryptedDEK = encryptedDEK,
                keyVersion = keyVersion.toLong(),
                updatedAt = updatedAt,
                id = id
            ).let { rowsUpdated ->
                ensure(rowsUpdated == 1L) { UpdateEncryptedSecretError.NotFound(id) }
            }
        }
    }

    override suspend fun getById(id: Long): Either<EncryptedSecretError.NotFound, EncryptedSecretEntity> =
        transactionScope.execute {
            either {
                queries.getById(id)
                    .executeAsOneOrNull()
                    ?.let { row ->
                        EncryptedSecretEntity(
                            id = row.id,
                            encryptedSecret = row.encryptedSecret,
                            encryptedDEK = row.encryptedDEK,
                            keyVersion = row.keyVersion.toInt(),
                            createdAt = row.createdAt,
                            updatedAt = row.updatedAt
                        )
                    } ?: raise(EncryptedSecretError.NotFound(id))
            }
        }

    override suspend fun deleteById(id: Long): Either<DeleteEncryptedSecretError, Unit> = transactionScope.execute {
        either {
            catch({
                ensure(queries.deleteById(id) == 1L) { DeleteEncryptedSecretError.NotFound(id) }
            }) { e: Throwable ->
                ensure(!e.isForeignKeyConstraintException()) {
                    DeleteEncryptedSecretError.ForeignKeyViolation(
                        "Failed to delete secret with id=$id because it is still referenced by another table.",
                        e
                    )
                }
                throw e
            }

        }
    }

    override suspend fun getAll(): List<EncryptedSecretEntity> = transactionScope.execute {
        queries.getAll()
            .executeAsList()
            .map { row ->
                EncryptedSecretEntity(
                    id = row.id,
                    encryptedSecret = row.encryptedSecret,
                    encryptedDEK = row.encryptedDEK,
                    keyVersion = row.keyVersion.toInt(),
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt
                )
            }
    }
}
