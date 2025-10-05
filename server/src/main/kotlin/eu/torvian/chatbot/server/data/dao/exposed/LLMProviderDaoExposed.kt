package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
import eu.torvian.chatbot.server.data.tables.LLMProviderTable
import eu.torvian.chatbot.server.data.tables.mappers.toLLMProvider
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [LLMProviderDao].
 */
class LLMProviderDaoExposed(
    private val transactionScope: TransactionScope
) : LLMProviderDao {

    override suspend fun getAllProviders(): List<LLMProvider> =
        transactionScope.transaction {
            LLMProviderTable.selectAll()
                .map { it.toLLMProvider() }
        }

    override suspend fun getProviderById(id: Long): Either<LLMProviderError.LLMProviderNotFound, LLMProvider> =
        transactionScope.transaction {
            LLMProviderTable.selectAll().where { LLMProviderTable.id eq id }
                .singleOrNull()
                ?.toLLMProvider()
                ?.right()
                ?: LLMProviderError.LLMProviderNotFound(id).left()
        }

    override suspend fun insertProvider(
        apiKeyId: String?,
        name: String,
        description: String,
        baseUrl: String,
        type: LLMProviderType
    ): Either<LLMProviderError.ApiKeyAlreadyInUse, LLMProvider> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = LLMProviderTable.insert {
                        it[LLMProviderTable.apiKeyId] = apiKeyId
                        it[LLMProviderTable.name] = name
                        it[LLMProviderTable.description] = description
                        it[LLMProviderTable.baseUrl] = baseUrl
                        it[LLMProviderTable.type] = type
                    }

                    val generatedId = insertStatement[LLMProviderTable.id]

                    LLMProvider(
                        id = generatedId.value,
                        apiKeyId = apiKeyId,
                        name = name,
                        description = description,
                        baseUrl = baseUrl,
                        type = type
                    )
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) {
                        LLMProviderError.ApiKeyAlreadyInUse(apiKeyId ?: "null")
                    }
                    throw e
                }
            }
        }

    override suspend fun updateProvider(provider: LLMProvider): Either<LLMProviderError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRows = LLMProviderTable.update({ LLMProviderTable.id eq provider.id }) {
                        it[apiKeyId] = provider.apiKeyId
                        it[name] = provider.name
                        it[description] = provider.description
                        it[baseUrl] = provider.baseUrl
                        it[type] = provider.type
                    }

                    ensure(updatedRows != 0) { LLMProviderError.LLMProviderNotFound(provider.id) }
                }) { e: ExposedSQLException ->
                    ensure(!e.isUniqueConstraintViolation()) {
                        LLMProviderError.ApiKeyAlreadyInUse(provider.apiKeyId ?: "null")
                    }
                    throw e
                }
            }
        }

    override suspend fun deleteProvider(id: Long): Either<LLMProviderError.LLMProviderNotFound, Unit> =
        transactionScope.transaction {
            val deletedRows = LLMProviderTable.deleteWhere { LLMProviderTable.id eq id }
            
            if (deletedRows == 0) {
                LLMProviderError.LLMProviderNotFound(id).left()
            } else {
                Unit.right()
            }
        }


}
