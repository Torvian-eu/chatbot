package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.settings.AddSettingsError
import eu.torvian.chatbot.server.service.core.error.settings.DeleteSettingsError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.core.error.settings.UpdateSettingsError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [ModelSettingsService] interface.
 */
class ModelSettingsServiceImpl(
    private val settingsDao: SettingsDao,
    private val llmModelService: LLMModelService,
    private val transactionScope: TransactionScope,
    private val settingsOwnershipDao: SettingsOwnershipDao
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

    override suspend fun getAllAccessibleSettings(userId: Long, accessMode: AccessMode): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAllAccessibleSettings(userId, accessMode)
        }
    }

    override suspend fun getAccessibleSettingsByModelId(
        userId: Long,
        modelId: Long,
        accessMode: AccessMode
    ): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAccessibleSettingsByModelId(userId, modelId, accessMode)
        }
    }

    override suspend fun addSettings(ownerId: Long, settings: ModelSettings): Either<AddSettingsError, ModelSettings> =
        transactionScope.transaction {
            either {
                // Get the associated LLMModel to verify type consistency
                val llmModel = withError({ getModelError: GetModelError ->
                    when (getModelError) {
                        is GetModelError.ModelNotFound -> AddSettingsError.ModelNotFound(getModelError.id)
                    }
                }) {
                    llmModelService.getModelById(settings.modelId).bind()
                }
                ensure(settings.modelType == llmModel.type) {
                    AddSettingsError.InvalidInput(
                        "Model settings type (${settings.modelType}) does not match the associated LLM Model's type (${llmModel.type})."
                    )
                }

                // Insert the settings
                val createdModelSettings = withError({ daoError: SettingsError.ModelNotFound ->
                    AddSettingsError.ModelNotFound(daoError.modelId)
                }) {
                    settingsDao.insertSettings(settings).bind()
                }

                // Set ownership for the newly created settings
                withError({ daoError: SetOwnerError ->
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation -> AddSettingsError.OwnershipError("Failed to set settings ownership")
                        is SetOwnerError.AlreadyOwned -> AddSettingsError.OwnershipError("Settings ownership conflict")
                    }
                }) {
                    settingsOwnershipDao.setOwner(createdModelSettings.id, ownerId).bind()
                }

                createdModelSettings
            }
        }

    override suspend fun updateSettings(settings: ModelSettings): Either<UpdateSettingsError, Unit> =
        transactionScope.transaction {
            either {
                // Get the associated LLMModel to verify type consistency
                val llmModel = withError({ getModelError: GetModelError ->
                    when (getModelError) {
                        is GetModelError.ModelNotFound -> UpdateSettingsError.ModelNotFound(getModelError.id)
                    }
                }) {
                    llmModelService.getModelById(settings.modelId).bind()
                }

                // Verify that the ModelSettings type matches the LLMModel's type
                ensure(settings.modelType == llmModel.type) {
                    UpdateSettingsError.InvalidInput(
                        "Model settings type (${settings.modelType}) does not match the associated LLM Model's type (${llmModel.type})."
                    )
                }

                // Update the settings
                withError({ daoError: SettingsError ->
                    when (daoError) {
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
