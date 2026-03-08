package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.ProviderOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.LLMProviderOwnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [ProviderOwnershipDao].
 */
class ProviderOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : ProviderOwnershipDao {

    override suspend fun getOwner(providerId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            LLMProviderOwnersTable
                .selectAll()
                .where { LLMProviderOwnersTable.providerId eq providerId }
                .singleOrNull()
                ?.let { it[LLMProviderOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(providerId.toString()).left()
        }

    override suspend fun setOwner(providerId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    LLMProviderOwnersTable.insert {
                        it[LLMProviderOwnersTable.providerId] = providerId
                        it[LLMProviderOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(providerId.toString(), userId))

                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)

                        else -> throw e
                    }
                }
            }
        }
}
