package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.AddModelRequest
import eu.torvian.chatbot.common.models.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.LLMModel
import kotlinx.coroutines.flow.*

/**
 * Default implementation of [eu.torvian.chatbot.app.repository.ModelRepository] that follows the Single Source of Truth principle.
 *
 * This repository maintains a single StateFlow of all models and provides derived cold Flows
 * for individual models. It delegates state management responsibility to consumers (ViewModels),
 * keeping the repository focused on data access and manipulation.
 *
 * @property modelApi The API client for model-related operations
 */
class DefaultModelRepository(
    private val modelApi: ModelApi
) : ModelRepository {

    companion object {
        private val logger = kmpLogger<DefaultModelRepository>()
    }

    private val _models = MutableStateFlow<DataState<RepositoryError, List<LLMModel>>>(DataState.Idle)
    override val models: StateFlow<DataState<RepositoryError, List<LLMModel>>> = _models.asStateFlow()

    override fun getModelFlow(modelId: Long): Flow<DataState<RepositoryError, LLMModel>> {
        // Return a cold Flow that derives from the main models StateFlow
        return models.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val model = dataState.data.find { it.id == modelId }
                    if (model != null) {
                        DataState.Success(model)
                    } else {
                        logger.warn("Tried to get model $modelId but it's not in the list")
                        DataState.Error(RepositoryError.OtherError("Model with ID $modelId not found"))
                    }
                }

                is DataState.Error -> DataState.Error(dataState.error)
                is DataState.Loading -> DataState.Loading
                is DataState.Idle -> DataState.Idle
            }
        }
    }

    override suspend fun loadModels(): Either<RepositoryError, List<LLMModel>> {
        _models.update { DataState.Loading }

        return modelApi.getAllModels()
            .map { modelList ->
                _models.update { DataState.Success(modelList) }
                modelList
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load models")
                _models.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun loadModelDetails(modelId: Long): Either<RepositoryError, LLMModel> {
        return modelApi.getModelById(modelId)
            .map { model ->
                // Update the main list with the detailed model data
                updateModelsState { list ->
                    if (list.any { it.id == model.id })
                        list.map { if (it.id == model.id) model else it }
                    else list + model
                }
                model
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to load model details")
            }
    }

    override suspend fun addModel(request: AddModelRequest): Either<RepositoryError, LLMModel> {
        return modelApi.addModel(request)
            .map { newModel ->
                updateModelsState { it + newModel }
                newModel
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add model")
            }
    }

    override suspend fun updateModel(model: LLMModel): Either<RepositoryError, Unit> {
        return modelApi.updateModel(model)
            .map {
                updateModelsState { list -> list.map { if (it.id == model.id) model else it } }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update model")
            }
    }

    override suspend fun deleteModel(modelId: Long): Either<RepositoryError, Unit> {
        return modelApi.deleteModel(modelId)
            .map {
                updateModelsState { list -> list.filter { it.id != modelId } }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete model")
            }
    }

    override suspend fun getModelApiKeyStatus(modelId: Long): Either<RepositoryError, ApiKeyStatusResponse> {
        return modelApi.getModelApiKeyStatus(modelId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to get model API key status")
            }
    }

    /**
     * Updates the internal StateFlow of models using the provided transformation.
     */
    private fun updateModelsState(transform: (List<LLMModel>) -> List<LLMModel>) {
        _models.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> {
                    logger.warn("Tried to update models but they're not in Success state")
                    currentState
                }
            }
        }
    }
}
