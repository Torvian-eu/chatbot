package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ModelPresetOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.ModelPresetOwnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [ModelPresetOwnershipDao].
 *
 * @property transactionScope Transaction wrapper used for the DAO operations.
 */
class ModelPresetOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : ModelPresetOwnershipDao {

    override suspend fun getOwner(presetId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            ModelPresetOwnersTable
                .selectAll()
                .where { ModelPresetOwnersTable.presetId eq presetId }
                .singleOrNull()
                ?.let { it[ModelPresetOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(presetId.toString()).left()
        }

    override suspend fun setOwner(presetId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    ModelPresetOwnersTable.insert {
                        it[ModelPresetOwnersTable.presetId] = presetId
                        it[ModelPresetOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(presetId.toString(), userId))

                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)

                        else -> throw e
                    }
                }
            }
        }
}
