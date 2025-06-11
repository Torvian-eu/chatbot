package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.tables.LLMModelTable
import eu.torvian.chatbot.server.data.tables.mappers.toLLMModel
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Exposed implementation of the [ModelDao].
 */
class ModelDaoExposed(
    private val transactionScope: TransactionScope
) : ModelDao {
    override suspend fun getAllModels(): List<LLMModel> =
        transactionScope.transaction {
            LLMModelTable.selectAll()
                .map { it.toLLMModel() }
        }

    override suspend fun getModelById(id: Long): Either<ModelError.ModelNotFound, LLMModel> =
        transactionScope.transaction {
            LLMModelTable.selectAll().where { LLMModelTable.id eq id }
                .singleOrNull()
                ?.toLLMModel()
                ?.right()
                ?: ModelError.ModelNotFound(id).left()
        }

    override suspend fun getModelByApiKeyId(apiKeyId: String): LLMModel? =
        transactionScope.transaction {
            LLMModelTable.selectAll().where { LLMModelTable.apiKeyId eq apiKeyId }
                .singleOrNull()
                ?.toLLMModel()
        }

    override suspend fun insertModel(name: String, baseUrl: String, type: String, apiKeyId: String?): LLMModel =
        transactionScope.transaction {
            val insertStatement = LLMModelTable.insert {
                it[LLMModelTable.name] = name
                it[LLMModelTable.baseUrl] = baseUrl
                it[LLMModelTable.type] = type
                it[LLMModelTable.apiKeyId] = apiKeyId
            }
            insertStatement.resultedValues?.first()?.toLLMModel()
                ?: throw IllegalStateException("Failed to retrieve newly inserted model")
        }

    override suspend fun updateModel(model: LLMModel): Either<ModelError.ModelNotFound, Unit> =
        transactionScope.transaction {
            either {
                val updatedRowCount = LLMModelTable.update({ LLMModelTable.id eq model.id }) {
                    it[name] = model.name
                    it[baseUrl] = model.baseUrl
                    it[type] = model.type
                    it[apiKeyId] = model.apiKeyId
                }
                ensure(updatedRowCount != 0) { ModelError.ModelNotFound(model.id) }
            }
        }

    override suspend fun deleteModel(id: Long): Either<ModelError.ModelNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = LLMModelTable.deleteWhere { LLMModelTable.id eq id }
                ensure(deletedCount != 0) { ModelError.ModelNotFound(id) }
            }
        }
}
