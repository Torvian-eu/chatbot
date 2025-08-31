package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ModelConfigData
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.domain.contracts.UiState
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
 * @property isAddingNewModel UI state indicating if the "Add New Model" form is visible (E4.S1).
 * @property modelForm The unified state of the model form (for both adding and editing).
 * @property editingModel The model currently being edited (E4.S3). Null if no model is being edited.
 */
class ModelConfigViewModel(
    private val modelApi: ModelApi,
    private val providerApi: ProviderApi, // Needed to populate provider dropdown for model config
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Observable State for Compose UI ---

    private val _modelConfigState = MutableStateFlow<UiState<ApiError, ModelConfigData>>(UiState.Idle)

    /**
     * The unified state containing both models and providers data.
     * This replaces the separate modelsState and providersForSelection properties.
     */
    val modelConfigState: StateFlow<UiState<ApiError, ModelConfigData>> = _modelConfigState.asStateFlow()

    /**
     * Helper property to get the current config data if in success state, null otherwise.
     * Follows the Law of Demeter and provides a clean way to check for success state.
     */
    private val currentConfigData: ModelConfigData?
        get() = _modelConfigState.value.dataOrNull

    /**
     * Helper property to check if the model config state is successfully loaded.
     * More descriptive than checking for null data.
     */
    private val isConfigDataLoaded: Boolean
        get() = _modelConfigState.value is UiState.Success

    private val _isAddingNewModel = MutableStateFlow(false)

    /**
     * Controls the visibility of the "Add New Model" form (E4.S1).
     */
    val isAddingNewModel: StateFlow<Boolean> = _isAddingNewModel.asStateFlow()

    private val _modelForm = MutableStateFlow(ModelFormState())

    /**
     * Holds the unified form state for model configuration (both adding new and editing existing).
     */
    val modelForm: StateFlow<ModelFormState> = _modelForm.asStateFlow()

    private val _editingModel = MutableStateFlow<LLMModel?>(null)

    /**
     * The model currently being edited (E4.S3). Null if no model is being edited.
     */
    val editingModel: StateFlow<LLMModel?> = _editingModel.asStateFlow()

    private val _selectedModel = MutableStateFlow<LLMModel?>(null)

    /**
     * The currently selected model in the master-detail UI.
     */
    val selectedModel: StateFlow<LLMModel?> = _selectedModel.asStateFlow()


    // --- Public Action Functions ---

    /**
     * Selects a model (or clears selection when null).
     */
    fun selectModel(model: LLMModel?) {
        _selectedModel.value = model
    }

    /**
     * Loads all configured LLM models (E4.S2) and providers (for selection dropdown).
     * Uses parZip for concurrent loading.
     */
    fun loadModelsAndProviders() {
        if (_modelConfigState.value.isLoading) return // Prevent duplicate loading

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
     * Initiates the process of adding a new model by showing the form (E4.S1).
     */
    fun startAddingNewModel() {
        // Only allow adding new models if we have successfully loaded data
        val configData = currentConfigData ?: return

        _isAddingNewModel.value = true
        _editingModel.value = null
        _modelForm.value = ModelFormState(
            mode = FormMode.NEW,
            // Pre-select first provider if available, otherwise null
            providerId = configData.providers.firstOrNull()?.id
        )
    }

    /**
     * Cancels the new model addition process.
     */
    fun cancelAddingNewModel() {
        _isAddingNewModel.value = false
        _modelForm.value = ModelFormState() // Clear form
    }

    /**
     * Updates any field in the model form using a lambda function.
     * This replaces all the individual update functions with a single, flexible approach.
     */
    fun updateModelForm(update: (ModelFormState) -> ModelFormState) {
        _modelForm.update { update(it).withError(null) }
    }

    /**
     * Saves the model configuration - either creates new or updates existing based on form mode.
     */
    fun saveModel() {
        // Only proceed if we have successfully loaded config data
        if (!isConfigDataLoaded) return

        val form = _modelForm.value
        if (form.name.isBlank() || form.providerId == null) {
            _modelForm.update { it.copy(errorMessage = "Model Name and Provider must be selected.") }
            return
        }

        when (form.mode) {
            FormMode.NEW -> addNewModel(form)
            FormMode.EDIT -> saveEditedModel(form)
        }
    }

    /**
     * Saves the new LLM model configuration to the backend (E4.S1).
     */
    private fun addNewModel(form: ModelFormState) {
        viewModelScope.launch(uiDispatcher) {
            val request = AddModelRequest(
                name = form.name.trim(),
                providerId = form.providerId!!, // Already validated as non-null
                type = form.type,
                active = form.active,
                displayName = form.displayName.trim().takeIf { it.isNotBlank() }
            )

            modelApi.addModel(request)
                .fold(
                    ifLeft = { error ->
                        _modelForm.update { it.copy(errorMessage = "Error adding model: ${error.message}") }
                        println("Error adding model: ${error.code} - ${error.message}")
                    },
                    ifRight = { newModel ->
                        // Add the new model to the list, maintaining the Success state (E4.S1)
                        updateModels { it + newModel }
                        cancelAddingNewModel() // Hide form and reset
                        selectModel(newModel)
                    }
                )
        }
    }

    /**
     * Initiates the editing process for an existing model (E4.S3).
     *
     * @param model The [LLMModel] to be edited.
     */
    fun startEditingModel(model: LLMModel) {
        // Only allow editing if we have successfully loaded data (for provider validation)
        if (!isConfigDataLoaded) return

        _editingModel.value = model
        _isAddingNewModel.value = false
        _modelForm.value = ModelFormState(
            mode = FormMode.EDIT,
            name = model.name,
            providerId = model.providerId,
            type = model.type,
            active = model.active,
            displayName = model.displayName ?: ""
        )
    }

    /**
     * Cancels the editing process for a model.
     */
    fun cancelEditingModel() {
        _editingModel.value = null
        _modelForm.value = ModelFormState() // Clear form
    }

    /**
     * Saves the details of an edited model (E4.S3).
     */
    private fun saveEditedModel(form: ModelFormState) {
        val originalModel = _editingModel.value ?: return

        viewModelScope.launch(uiDispatcher) {
            val updatedModel = originalModel.copy(
                name = form.name.trim(),
                providerId = form.providerId!!, // Already validated as non-null
                type = form.type,
                active = form.active,
                displayName = form.displayName.trim().takeIf { it.isNotBlank() }
            )

            modelApi.updateModel(updatedModel)
                .fold(
                    ifLeft = { error ->
                        _modelForm.update { it.copy(errorMessage = "Error updating model: ${error.message}") }
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
                        // Clear error messages related to this save
                        _modelForm.update { it.copy(errorMessage = null) }
                        // Close the edit dialog after successful save
                        cancelEditingModel()
                    }
                )
        }
    }

    /**
     * Deletes a specific LLM model configuration (E4.S4).
     *
     * @param modelId The ID of the model to delete.
     */
    fun deleteModel(modelId: Long) {
        // Only proceed if we have successfully loaded models
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
                        // If the deleted model was being edited, clear the editing state
                        if (_editingModel.value?.id == modelId) {
                            cancelEditingModel()
                        }
                        // If the deleted model was selected, clear the selection
                        if (_selectedModel.value?.id == modelId) {
                            _selectedModel.value = null
                        }
                    }
                )
        }
    }

    // --- Internal helpers ---

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
