package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.llm.AddModelRequest
import eu.torvian.chatbot.common.models.api.llm.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.llm.LLMModel
import kotlinx.coroutines.flow.*

/**
 * Default implementation of [ModelRepository] that follows the Single Source of Truth principle.
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

    private val _modelsDetails =
        MutableStateFlow<DataState<RepositoryError, List<LLMModelDetails>>>(DataState.Idle)
    override val modelsDetails: StateFlow<DataState<RepositoryError, List<LLMModelDetails>>> =
        _modelsDetails.asStateFlow()

    private val _models = MutableStateFlow<DataState<RepositoryError, List<LLMModel>>>(DataState.Idle)
    override val models: StateFlow<DataState<RepositoryError, List<LLMModel>>> = _models.asStateFlow()

    override fun getModelFlow(modelId: Long): Flow<DataState<RepositoryError, LLMModelDetails?>> {
        // Return a cold Flow that derives from the main modelDetails StateFlow
        return modelsDetails.map { dataState ->
            when (dataState) {
                is DataState.Success -> {
                    val model = dataState.data.find { it.model.id == modelId }
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

    override suspend fun loadModelsDetails(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_modelsDetails.value.isLoading) return Unit.right()

        _modelsDetails.update { DataState.Loading }

        return modelApi.getAllModelDetails()
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load all models (with details)")
                _modelsDetails.update { DataState.Error(repositoryError) }
                repositoryError
            }.map { detailsList ->
                _modelsDetails.update { DataState.Success(detailsList) }
            }
    }

    override suspend fun loadModels(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_models.value.isLoading) return Unit.right()

        _models.update { DataState.Loading }

        return modelApi.getAllModels()
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load all models")
                _models.update { DataState.Error(repositoryError) }
                repositoryError
            }.map { modelList ->
                _models.update { DataState.Success(modelList) }
            }
    }

    override suspend fun loadModelDetails(modelId: Long): Either<RepositoryError, LLMModelDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to load model details")
        }) {
            modelApi.getModelDetails(modelId).bind()
        }.also { modelDetails ->
            updateModelDetailsState { list ->
                if (list.any { it.model.id == modelDetails.model.id }) {
                    list.map { if (it.model.id == modelDetails.model.id) modelDetails else it }
                } else {
                    list + modelDetails
                }
            }
            updateModelState { list ->
                if (list.any { it.id == modelDetails.model.id }) {
                    list.map { if (it.id == modelDetails.model.id) modelDetails.model else it }
                } else {
                    list + modelDetails.model
                }
            }
        }
    }

    override suspend fun addModel(request: AddModelRequest): Either<RepositoryError, LLMModelDetails> =
        either {
            val newModel = withError({ apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add model")
            }) {
                modelApi.addModel(request).bind()
            }
            // After creation, fetch details to refresh cache
            loadModelDetails(newModel.id).bind()
        }

    override suspend fun updateModel(model: LLMModel): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to update model")
        }) {
            modelApi.updateModel(model).bind()
        }
        loadModelDetails(model.id).bind()
    }

    override suspend fun deleteModel(modelId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to delete model")
        }) {
            modelApi.deleteModel(modelId).bind()
        }
        updateModelDetailsState { list -> list.filter { it.model.id != modelId } }
        updateModelState { list -> list.filter { it.id != modelId } }
    }

    override suspend fun getModelApiKeyStatus(modelId: Long): Either<RepositoryError, ApiKeyStatusResponse> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to get model API key status")
        }) {
            modelApi.getModelApiKeyStatus(modelId).bind()
        }
    }

    override suspend fun makeModelPublic(modelId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to make model public")
        }) {
            modelApi.makeModelPublic(modelId).bind()
        }
        loadModelDetails(modelId).bind()
    }

    override suspend fun makeModelPrivate(modelId: Long): Either<RepositoryError, Unit> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to make model private")
        }) {
            modelApi.makeModelPrivate(modelId).bind()
        }
        loadModelDetails(modelId).bind()
    }

    override suspend fun grantModelAccess(
        modelId: Long,
        request: GrantAccessRequest
    ): Either<RepositoryError, LLMModelDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to grant model access")
        }) {
            modelApi.grantModelAccess(modelId, request).bind()
        }
        loadModelDetails(modelId).bind()
    }

    override suspend fun revokeModelAccess(
        modelId: Long,
        request: RevokeAccessRequest
    ): Either<RepositoryError, LLMModelDetails> = either {
        withError({ apiResourceError ->
            apiResourceError.toRepositoryError("Failed to revoke model access")
        }) {
            modelApi.revokeModelAccess(modelId, request).bind()
        }
        loadModelDetails(modelId).bind()
    }

    /**
     * Updates the internal StateFlow of model details using the provided transformation.
     *
     * @param transform A function that takes the current list of model details and returns an updated list.
     */
    private fun updateModelDetailsState(transform: (List<LLMModelDetails>) -> List<LLMModelDetails>) {
        _modelsDetails.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> {
                    logger.warn("Tried to update model details but they're not in Success state")
                    currentState
                }
            }
        }
    }

    /**
     * Updates the internal StateFlow of models using the provided transformation.
     *
     * @param transform A function that takes the current list of models and returns an updated list.
     */
    private fun updateModelState(transform: (List<LLMModel>) -> List<LLMModel>) {
        _models.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> {
                    logger.warn("Tried to update models but they're not in Success state")
                    currentState
                }
            }
        }
    }
}
