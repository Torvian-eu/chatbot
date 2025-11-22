package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.ModelOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.LLMModelOwnersTable
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/**
 * Exposed implementation of the [ModelOwnershipDao].
 */
class ModelOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : ModelOwnershipDao {

    override suspend fun getOwner(modelId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            LLMModelOwnersTable
                .selectAll()
                .where { LLMModelOwnersTable.modelId eq modelId }
                .singleOrNull()
                ?.let { it[LLMModelOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(modelId.toString()).left()
        }

    override suspend fun setOwner(modelId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    LLMModelOwnersTable.insert {
                        it[LLMModelOwnersTable.modelId] = modelId
                        it[LLMModelOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(modelId.toString(), userId))
                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)
                        else -> throw e
                    }
                }
            }
        }
}

