package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.tables.ModelSettingsOwnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Exposed implementation of the [SettingsOwnershipDao].
 */
class SettingsOwnershipDaoExposed(
    private val transactionScope: TransactionScope
) : SettingsOwnershipDao {

    override suspend fun getOwner(settingsId: Long): Either<GetOwnerError, Long> =
        transactionScope.transaction {
            ModelSettingsOwnersTable
                .selectAll()
                .where { ModelSettingsOwnersTable.settingsId eq settingsId }
                .singleOrNull()
                ?.let { it[ModelSettingsOwnersTable.userId].value }
                ?.right()
                ?: GetOwnerError.ResourceNotFound(settingsId.toString()).left()
        }

    override suspend fun setOwner(settingsId: Long, userId: Long): Either<SetOwnerError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    ModelSettingsOwnersTable.insert {
                        it[ModelSettingsOwnersTable.settingsId] = settingsId
                        it[ModelSettingsOwnersTable.userId] = userId
                    }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() ->
                            raise(SetOwnerError.ForeignKeyViolation(settingsId.toString(), userId))

                        e.isUniqueConstraintViolation() ->
                            raise(SetOwnerError.AlreadyOwned)

                        else -> throw e
                    }
                }
            }
        }
}

