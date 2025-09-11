package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.ProviderRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.AddModelRequest
import eu.torvian.chatbot.common.models.LLMModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for configuring LLM Models (E4.S*).
 *
 * This ViewModel handles:
 * - Loading and displaying a list of configured LLM models (E4.S2).
 * - Providing a list of LLM providers for model selection (E4.S1).
 * - Managing the state for adding new models (E4.S1).
 * - Managing the state for editing existing model details (E4.S3).
 * - Deleting models (E4.S4).
 * - Communicating with the backend via [ModelRepository] and [ProviderRepository].
 *
 * @constructor
 * @param modelRepository The repository for LLM Model-related operations.
 * @param providerRepository The repository for LLM Provider-related operations.
 * @param errorNotifier The service for handling and notifying about errors.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property modelConfigState The unified state containing both models and providers data.
 * @property selectedModel The currently selected model in the master-detail UI.
 * @property dialogState The current dialog state for the models tab.
 */
class ModelConfigViewModel(
    private val modelRepository: ModelRepository,
    private val providerRepository: ProviderRepository, // Needed to populate provider dropdown for model config
    private val errorNotifier: ErrorNotifier,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    /**
     * The ID of the model the user has explicitly selected.
     */
    private val userSelectedModelId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<ModelsDialogState>(ModelsDialogState.None)

    // --- Public State Properties ---

    /**
     * The state containing both models and providers data.
     */
    val modelConfigState: StateFlow<DataState<RepositoryError, ModelConfigData>> = combine(
        modelRepository.models,
        providerRepository.providers
    ) { modelsState, providersState ->
        when {
            modelsState is DataState.Loading || providersState is DataState.Loading -> DataState.Loading
            modelsState is DataState.Error -> DataState.Error(modelsState.error)
            providersState is DataState.Error -> DataState.Error(providersState.error)
            modelsState is DataState.Success && providersState is DataState.Success -> {
                val configData = ModelConfigData(
                    models = modelsState.data,
                    providers = providersState.data
                )
                DataState.Success(configData)
            }

            else -> DataState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DataState.Idle)

    /**
     * The currently selected model in the master-detail UI.
     */
    val selectedModel: StateFlow<LLMModel?> = combine(
        modelConfigState.map { it.dataOrNull?.models },
        userSelectedModelId
    ) { modelsList, currentSelectedId ->
        modelsList?.find { it.id == currentSelectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The current dialog state for the models tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<ModelsDialogState> = _dialogState.asStateFlow()

    // --- Helper Properties ---

    /**
     * Helper property to get the current config data if in success state, null otherwise.
     */
    private val currentConfigData: ModelConfigData?
        get() = modelConfigState.value.dataOrNull

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models (E4.S2) and providers (for selection dropdown).
     *
     * With the repository pattern, this method triggers loading in both repositories.
     * The reactive data streams in the init block will automatically update the UI state.
     */
    fun loadModelsAndProviders() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { modelRepository.loadModels() },
                { providerRepository.loadProviders() }
            ) { modelsResult, providersResult ->
                modelsResult.mapLeft { error ->
                    errorNotifier.repositoryError(
                        error = error,
                        shortMessage = "Failed to load models"
                    )
                }
                providersResult.mapLeft { error ->
                    errorNotifier.repositoryError(
                        error = error,
                        shortMessage = "Failed to load providers for model selection"
                    )
                }
            }
        }
    }

    /**
     * Selects a model (or clears selection when null).
     */
    fun selectModel(model: LLMModel?) {
        userSelectedModelId.value = model?.id
    }

    /**
     * Initiates the process of adding a new model by showing the form (E4.S1).
     */
    fun startAddingNewModel() {
        val configData = currentConfigData ?: return

        _dialogState.value = ModelsDialogState.AddNewModel(
            formState = ModelFormState(
                mode = FormMode.NEW,
                // Pre-select first provider if available, otherwise null
                providerId = configData.providers.firstOrNull()?.id
            ),
            providers = configData.providers
        )
    }

    /**
     * Initiates the editing process for an existing model (E4.S3).
     *
     * @param model The [LLMModel] to be edited.
     */
    fun startEditingModel(model: LLMModel) {
        val configData = currentConfigData ?: return

        _dialogState.value = ModelsDialogState.EditModel(
            model = model,
            formState = ModelFormState.fromModel(model),
            providers = configData.providers
        )
    }

    /**
     * Initiates the deletion process for a model by showing the confirmation dialog.
     *
     * @param model The [LLMModel] to be deleted.
     */
    fun startDeletingModel(model: LLMModel) {
        _dialogState.value = ModelsDialogState.DeleteModel(model)
    }

    /**
     * Updates any field in the model form using a lambda function.
     */
    fun updateModelForm(update: (ModelFormState) -> ModelFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is ModelsDialogState.AddNewModel -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                is ModelsDialogState.EditModel -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                else -> dialogState // No change for other states
            }
        }
    }

    /**
     * Saves the model configuration - either creates new or updates existing based on the dialog state.
     */
    fun saveModel() {
        when (val dialogState = _dialogState.value) {
            is ModelsDialogState.AddNewModel -> addNewModel(dialogState)
            is ModelsDialogState.EditModel -> saveEditedModel(dialogState)
            else -> return
        }
    }

    /**
     * Deletes a specific LLM model configuration (E4.S4).
     *
     * @param modelId The ID of the model to delete.
     */
    fun deleteModel(modelId: Long) {
        viewModelScope.launch(uiDispatcher) {
            modelRepository.deleteModel(modelId)
                .fold(
                    ifLeft = { error ->
                        errorNotifier.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete model"
                        )
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Closes any open dialog by setting state to None.
     */
    fun cancelDialog() {
        _dialogState.value = ModelsDialogState.None
    }

    // --- Private Helper Functions ---

    /**
     * Saves the new LLM model configuration to the backend (E4.S1).
     */
    private fun addNewModel(dialogState: ModelsDialogState.AddNewModel) {
        val form = dialogState.formState

        if (form.name.isBlank() || form.providerId == null) {
            updateModelForm { it.copy(errorMessage = "Model Name and Provider must be selected.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val request = AddModelRequest(
                name = form.name.trim(),
                providerId = form.providerId,
                type = form.type,
                active = form.active,
                displayName = form.displayName.trim().takeIf { it.isNotBlank() }
            )

            modelRepository.addModel(request)
                .fold(
                    ifLeft = { error ->
                        errorNotifier.repositoryError(
                            error = error,
                            shortMessage = "Failed to add model"
                        )
                        updateModelForm { it.copy(errorMessage = "Error adding model: ${error.message}") }
                    },
                    ifRight = { newModel ->
                        cancelDialog()
                        selectModel(newModel)
                    }
                )
        }
    }

    /**
     * Saves the details of an edited model (E4.S3).
     */
    private fun saveEditedModel(dialogState: ModelsDialogState.EditModel) {
        val form = dialogState.formState
        val originalModel = dialogState.model

        if (form.name.isBlank() || form.providerId == null) {
            updateModelForm { it.copy(errorMessage = "Model Name and Provider must be selected.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val updatedModel = originalModel.copy(
                name = form.name.trim(),
                providerId = form.providerId,
                type = form.type,
                active = form.active,
                displayName = form.displayName.trim().takeIf { it.isNotBlank() }
            )

            modelRepository.updateModel(updatedModel)
                .fold(
                    ifLeft = { error ->
                        errorNotifier.repositoryError(
                            error = error,
                            shortMessage = "Failed to update model"
                        )
                        updateModelForm { it.copy(errorMessage = "Error updating model: ${error.message}") }
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }
}
