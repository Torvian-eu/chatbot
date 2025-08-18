package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.*
import arrow.core.raise.*
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.data.tables.mappers.toModelSettings
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import eu.torvian.chatbot.server.data.tables.ModelSettingsTable as ModelSettingsTable

/**
 * Exposed implementation of the [SettingsDao].
 */
class SettingsDaoExposed(
    private val transactionScope: TransactionScope
) : SettingsDao {
    override suspend fun getSettingsById(id: Long): Either<SettingsError.SettingsNotFound, ModelSettings> =
        transactionScope.transaction {
            ModelSettingsTable
                .selectAll().where { ModelSettingsTable.id eq id }
                .singleOrNull()
                ?.toModelSettings()
                ?.right()
                ?: SettingsError.SettingsNotFound(id).left()
        }

    override suspend fun getAllSettings(): List<ModelSettings> =
        transactionScope.transaction {
            ModelSettingsTable
                .selectAll()
                .map { it.toModelSettings() }
        }

    override suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> =
        transactionScope.transaction {
            ModelSettingsTable
                .selectAll().where { ModelSettingsTable.modelId eq modelId }
                .map { it.toModelSettings() }
        }

    override suspend fun insertSettings(
        name: String,
        modelId: Long,
        systemMessage: String?,
        temperature: Float?,
        maxTokens: Int?,
        customParams: JsonObject?
    ): Either<SettingsError.ModelNotFound, ModelSettings> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = ModelSettingsTable.insert {
                        it[ModelSettingsTable.name] = name
                        it[ModelSettingsTable.modelId] = modelId
                        it[ModelSettingsTable.systemMessage] = systemMessage
                        it[ModelSettingsTable.temperature] = temperature
                        it[ModelSettingsTable.maxTokens] = maxTokens
                        it[ModelSettingsTable.customParams] = customParams?.let { params ->
                            Json.encodeToString(params)
                        }
                    }
                    insertStatement.resultedValues?.first()?.toModelSettings()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted settings")
                }) { e: ExposedSQLException ->
                    ensure(!e.isForeignKeyViolation()) { SettingsError.ModelNotFound(modelId) }
                    throw e
                }
            }
        }

    override suspend fun updateSettings(settings: ModelSettings): Either<SettingsError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = ModelSettingsTable.update({ ModelSettingsTable.id eq settings.id }) {
                        it[name] = settings.name
                        it[modelId] = settings.modelId
                        it[systemMessage] = settings.systemMessage
                        it[temperature] = settings.temperature
                        it[maxTokens] = settings.maxTokens
                        it[customParams] = settings.customParams?.let { params ->
                            Json.encodeToString(JsonObject.serializer(), params)
                        }
                    }
                    ensure(updatedRowCount != 0) { SettingsError.SettingsNotFound(settings.id) }
                }) { e: ExposedSQLException ->
                    ensure(!e.isForeignKeyViolation()) { SettingsError.ModelNotFound(settings.modelId) }
                    throw e
                }
            }
        }

    override suspend fun deleteSettings(id: Long): Either<SettingsError.SettingsNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = ModelSettingsTable.deleteWhere { ModelSettingsTable.id eq id }
                ensure(deletedCount != 0) { SettingsError.SettingsNotFound(id) }
            }
        }
}
