package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.*
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.settings.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.serialization.json.JsonObject

/**
 * Implementation of the [ModelSettingsService] interface.
 */
class ModelSettingsServiceImpl(
    private val settingsDao: SettingsDao,
    private val transactionScope: TransactionScope,
) : ModelSettingsService {

    override suspend fun getSettingsById(id: Long): Either<GetSettingsByIdError, ModelSettings> =
        transactionScope.transaction {
            either {
                withError({ daoError: SettingsError.SettingsNotFound ->
                    GetSettingsByIdError.SettingsNotFound(daoError.id)
                }) {
                    settingsDao.getSettingsById(id).bind()
                }
            }
        }

    override suspend fun getAllSettings(): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAllSettings()
        }
    }

    override suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getSettingsByModelId(modelId)
        }
    }

    override suspend fun addSettings(
        name: String, modelId: Long, systemMessage: String?,
        temperature: Float?, maxTokens: Int?, customParams: JsonObject?
    ): Either<AddSettingsError, ModelSettings> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    AddSettingsError.InvalidInput("Settings name cannot be blank.")
                }
                ensure(!(temperature != null && (temperature < 0f || temperature > 2f))) {
                    AddSettingsError.InvalidInput("Temperature must be between 0.0 and 2.0")
                }
                ensure(!(maxTokens != null && maxTokens <= 0)) {
                    AddSettingsError.InvalidInput("Max tokens must be positive")
                }

                withError({ daoError: SettingsError.ModelNotFound ->
                    AddSettingsError.ModelNotFound(daoError.modelId)
                }) {
                    settingsDao.insertSettings(name, modelId, systemMessage, temperature, maxTokens, customParams).bind()
                }
            }
        }

    override suspend fun updateSettings(settings: ModelSettings): Either<UpdateSettingsError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!settings.name.isBlank()) {
                    UpdateSettingsError.InvalidInput("Settings name cannot be blank.")
                }
                val temperature = settings.temperature
                ensure(!(temperature != null && (temperature < 0f || temperature > 2f))) {
                    UpdateSettingsError.InvalidInput("Temperature must be between 0.0 and 2.0")
                }
                val maxTokens = settings.maxTokens
                ensure(!(maxTokens != null && maxTokens <= 0)) {
                    UpdateSettingsError.InvalidInput("Max tokens must be positive")
                }

                withError({ daoError: SettingsError ->
                    when(daoError) {
                        is SettingsError.SettingsNotFound -> UpdateSettingsError.SettingsNotFound(daoError.id)
                        is SettingsError.ModelNotFound -> UpdateSettingsError.ModelNotFound(daoError.modelId)
                    }
                }) {
                    settingsDao.updateSettings(settings).bind()
                }
            }
        }

    override suspend fun deleteSettings(id: Long): Either<DeleteSettingsError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SettingsError.SettingsNotFound ->
                    DeleteSettingsError.SettingsNotFound(daoError.id)
                }) {
                    settingsDao.deleteSettings(id).bind()
                }
            }
        }
}
