package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.app.utils.misc.ioDispatcher
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.AddModelRequest
import eu.torvian.chatbot.common.models.LLMModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * - Communicating with the backend via [ModelApi] and [ProviderApi].
 *
 * @constructor
 * @param modelApi The API client for LLM Model-related operations.
 * @param providerApi The API client for LLM Provider-related operations.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property modelConfigState The unified state containing both models and providers data.
 * @property selectedModel The currently selected model in the master-detail UI.
 * @property dialogState The current dialog state for the models tab.
 */
class ModelConfigViewModel(
    private val modelApi: ModelApi,
    private val providerApi: ProviderApi, // Needed to populate provider dropdown for model config
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    private val _modelConfigState = MutableStateFlow<UiState<ApiError, ModelConfigData>>(UiState.Idle)
    private val _selectedModel = MutableStateFlow<LLMModel?>(null)
    private val _dialogState = MutableStateFlow<ModelsDialogState>(ModelsDialogState.None)

    // --- Public State Properties ---

    /**
     * The state containing both models and providers data.
     */
    val modelConfigState: StateFlow<UiState<ApiError, ModelConfigData>> = _modelConfigState.asStateFlow()

    /**
     * The currently selected model in the master-detail UI.
     */
    val selectedModel: StateFlow<LLMModel?> = _selectedModel.asStateFlow()

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
        get() = _modelConfigState.value.dataOrNull

    /**
     * Helper property to check if the model config state is successfully loaded.
     */
    private val isConfigDataLoaded: Boolean
        get() = _modelConfigState.value is UiState.Success

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models (E4.S2) and providers (for selection dropdown).
     * Uses parZip for concurrent loading.
     */
    fun loadModelsAndProviders() {
        if (_modelConfigState.value.isLoading) return

        viewModelScope.launch(uiDispatcher) {
            _modelConfigState.value = UiState.Loading

            parZip(
                ioDispatcher,
                { modelApi.getAllModels() },
                { providerApi.getAllProviders() }
            ) { modelsEither, providersEither ->
                // Combine both results into a single state update
                val modelsResult = modelsEither.fold(
                    ifLeft = { error ->
                        _modelConfigState.value = UiState.Error(error)
                        println("Error loading models: ${error.code} - ${error.message}")
                        return@parZip
                    },
                    ifRight = { it }
                )

                val providersResult = providersEither.fold(
                    ifLeft = { error ->
                        _modelConfigState.value = UiState.Error(error)
                        println("Error loading providers for model selection: ${error.code} - ${error.message}")
                        return@parZip
                    },
                    ifRight = { it }
                )

                // Both succeeded, combine into ModelConfigData
                _modelConfigState.value = UiState.Success(
                    ModelConfigData(
                        models = modelsResult,
                        providers = providersResult
                    )
                )

                // Keep selected model in sync with refreshed list
                syncSelectedModelWithList(modelsResult)
            }
        }
    }

    /**
     * Selects a model (or clears selection when null).
     */
    fun selectModel(model: LLMModel?) {
        _selectedModel.value = model
    }

    /**
     * Initiates the process of adding a new model by showing the form (E4.S1).
     */
    fun startAddingNewModel() {
        // Only proceed if we have successfully loaded config data
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
        // Only proceed if we have successfully loaded config data
        val configData = currentConfigData ?: return

        // Set the dialog state to show the edit form
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
        // Only proceed if we have successfully loaded config data
        if (!isConfigDataLoaded) return

        // Set the dialog state to show the delete confirmation dialog
        _dialogState.value = ModelsDialogState.DeleteModel(model)
    }

    /**
     * Updates any field in the model form using a lambda function.
     */
    fun updateModelForm(update: (ModelFormState) -> ModelFormState) {
        // Update the form state within the dialog state
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
        // Only proceed if we have successfully loaded config data
        if (!isConfigDataLoaded) return

        // Pass the entire dialog state instead of just the form
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
        // Only proceed if we have successfully loaded config data
        if (!isConfigDataLoaded) return

        viewModelScope.launch(uiDispatcher) {
            modelApi.deleteModel(modelId)
                .fold(
                    ifLeft = { error ->
                        // Present the error to the user. For a conflict error (RESOURCE_IN_USE),
                        // provide more specific feedback as per E4.S4.
                        val errorMessage = when (error.code) {
                            CommonApiErrorCodes.RESOURCE_IN_USE.code ->
                                "Cannot delete model. It is currently linked to one or more chat sessions. Please update associated sessions first."

                            else -> "Error deleting model: ${error.message}"
                        }
                        // This message should ideally be displayed via a transient UI channel (e.g., toast, SnackBar)
                        // rather than altering the main UiState directly for a list.
                        println(errorMessage) // For now, logging to console
                    },
                    ifRight = {
                        // Remove the deleted model from the list (E4.S4)
                        updateModels { it.filter { model -> model.id != modelId } }
                        // Close the delete confirmation dialog after successful deletion
                        cancelDialog()
                        // If the deleted model was selected, clear the selection
                        if (_selectedModel.value?.id == modelId) {
                            _selectedModel.value = null
                        }
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
            // Update error message in the form state
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

            modelApi.addModel(request)
                .fold(
                    ifLeft = { error ->
                        // Update the error message in the form state
                        updateModelForm { it.copy(errorMessage = "Error adding model: ${error.message}") }
                        println("Error adding model: ${error.code} - ${error.message}")
                    },
                    ifRight = { newModel ->
                        // Add the new model to the list, maintaining the Success state (E4.S1)
                        updateModels { it + newModel }
                        cancelDialog() // Hide form and reset
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
            // Update error message in the form state
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

            modelApi.updateModel(updatedModel)
                .fold(
                    ifLeft = { error ->
                        // Update the error message in the form state
                        updateModelForm { it.copy(errorMessage = "Error updating model: ${error.message}") }
                        println("Error updating model: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Update the model in the list (E4.S3)
                        updateModels { models ->
                            models.map { model ->
                                if (model.id == updatedModel.id) updatedModel else model
                            }
                        }
                        // Sync the selected model instance if it's the one updated
                        if (_selectedModel.value?.id == updatedModel.id) {
                            _selectedModel.value = updatedModel
                        }
                        // Close the edit dialog after successful save
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Convenience function to update only the models list in the config state.
     * This is the most common operation - updating models while keeping providers unchanged.
     */
    private fun updateModels(update: (List<LLMModel>) -> List<LLMModel>) {
        _modelConfigState.update { state ->
            val currentData = state.dataOrNull ?: return@update state
            UiState.Success(currentData.copy(models = update(currentData.models)))
        }
    }

    /**
     * Ensures that the currently selected model instance is synchronized with the latest list of models.
     * This is important after operations that refresh the model list, such as loading or updating models.
     *
     * @param list The latest list of [LLMModel]s.
     */
    private fun syncSelectedModelWithList(list: List<LLMModel>) {
        val sel = _selectedModel.value ?: return
        _selectedModel.value = list.find { it.id == sel.id }
    }
}
