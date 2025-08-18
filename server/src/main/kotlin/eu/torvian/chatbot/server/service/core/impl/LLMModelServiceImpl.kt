package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.*
import arrow.core.raise.*
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMModelType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.InsertModelError
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError as DaoUpdateModelError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.serialization.json.JsonObject

/**
 * Implementation of the [LLMModelService] interface.
 */
class LLMModelServiceImpl(
    private val modelDao: ModelDao,
    private val llmProviderDao: LLMProviderDao,
    private val transactionScope: TransactionScope,
) : LLMModelService {

    override suspend fun getAllModels(): List<LLMModel> {
        return transactionScope.transaction {
            modelDao.getAllModels()
        }
    }

    override suspend fun getModelById(id: Long): Either<GetModelError, LLMModel> =
        transactionScope.transaction {
            either {
                withError({ daoError: ModelError.ModelNotFound ->
                    GetModelError.ModelNotFound(daoError.id)
                }) {
                    modelDao.getModelById(id).bind()
                }
            }
        }

    override suspend fun getModelsByProviderId(providerId: Long): List<LLMModel> =
        transactionScope.transaction {
            modelDao.getModelsByProviderId(providerId)
        }

    override suspend fun addModel(
        name: String,
        providerId: Long,
        type: LLMModelType,
        active: Boolean,
        displayName: String?,
        capabilities: JsonObject?
    ): Either<AddModelError, LLMModel> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    AddModelError.InvalidInput("Model name cannot be blank.")
                }

                withError({ daoError: InsertModelError ->
                    when (daoError) {
                        is InsertModelError.ProviderNotFound -> AddModelError.ProviderNotFound(daoError.providerId)
                        is InsertModelError.ModelNameAlreadyExists -> AddModelError.ModelNameAlreadyExists(daoError.name)
                    }
                }) {
                    modelDao.insertModel(name, providerId, type, active, displayName, capabilities).bind()
                }
            }
        }

    override suspend fun updateModel(model: LLMModel): Either<UpdateModelError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!model.name.isBlank()) {
                    UpdateModelError.InvalidInput("Model name cannot be blank.")
                }

                withError({ daoError: DaoUpdateModelError ->
                    when (daoError) {
                        is DaoUpdateModelError.ModelNotFound -> UpdateModelError.ModelNotFound(daoError.id)
                        is DaoUpdateModelError.ProviderNotFound -> UpdateModelError.ProviderNotFound(daoError.providerId)
                        is DaoUpdateModelError.ModelNameAlreadyExists -> UpdateModelError.ModelNameAlreadyExists(daoError.name)
                    }
                }) {
                    modelDao.updateModel(model).bind()
                }
            }
        }

    override suspend fun deleteModel(id: Long): Either<DeleteModelError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: ModelError.ModelNotFound ->
                    DeleteModelError.ModelNotFound(daoError.id)
                }) {
                    modelDao.deleteModel(id).bind()
                }
            }
        }

    override suspend fun isApiKeyConfiguredForModel(modelId: Long): Boolean {
        return transactionScope.transaction {
            modelDao.getModelById(modelId)
                .map { model ->
                    // Get the provider and check if it has an API key
                    llmProviderDao.getProviderById(model.providerId)
                        .map { provider ->
                            provider.apiKeyId != null
                        }
                        .getOrElse { false }
                }
                .getOrElse { false }
        }
    }
}
