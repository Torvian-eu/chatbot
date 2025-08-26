package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.app.utils.misc.ioDispatcher
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.AddModelRequest
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMModelType
import eu.torvian.chatbot.common.models.LLMProvider
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
 * @property modelsState The state of the list of all configured LLM models (E4.S2).
 * @property providersForSelection The state of the list of LLM providers available for selection when adding/editing models (E4.S1).
 * @property isAddingNewModel UI state indicating if the "Add New Model" form is visible (E4.S1).
 * @property newModelForm The state of the "Add New Model" form (E4.S1).
 * @property editingModel The model currently being edited (E4.S3). Null if no model is being edited.
 * @property editingModelForm The state of the "Edit Model" form (E4.S3).
 */
class ModelConfigViewModel(
    private val modelApi: ModelApi,
    private val providerApi: ProviderApi, // Needed to populate provider dropdown for model config
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Observable State for Compose UI ---

    private val _modelsState = MutableStateFlow<UiState<ApiError, List<LLMModel>>>(UiState.Idle)

    /**
     * The state of the list of all configured LLM models (E4.S2).
     * Includes loading, success with data, or error states.
     */
    val modelsState: StateFlow<UiState<ApiError, List<LLMModel>>> = _modelsState.asStateFlow()

    private val _providersForSelection = MutableStateFlow<UiState<ApiError, List<LLMProvider>>>(UiState.Idle)

    /**
     * The state of the list of LLM providers available for selection when adding/editing models (E4.S1).
     * This list is used to populate dropdowns in model configuration forms.
     */
    val providersForSelection: StateFlow<UiState<ApiError, List<LLMProvider>>> = _providersForSelection.asStateFlow()

    private val _isAddingNewModel = MutableStateFlow(false)

    /**
     * Controls the visibility of the "Add New Model" form (E4.S1).
     */
    val isAddingNewModel: StateFlow<Boolean> = _isAddingNewModel.asStateFlow()

    private val _newModelForm = MutableStateFlow(NewModelFormState())

    /**
     * Holds the data for the "Add New Model" form (E4.S1).
     */
    val newModelForm: StateFlow<NewModelFormState> = _newModelForm.asStateFlow()

    private val _editingModel = MutableStateFlow<LLMModel?>(null)

    /**
     * The model currently being edited (E4.S3). Null if no model is being edited.
     */
    val editingModel: StateFlow<LLMModel?> = _editingModel.asStateFlow()

    private val _editingModelForm = MutableStateFlow(EditModelFormState())

    /**
     * Holds the data for the "Edit Model" form (E4.S3).
     */
    val editingModelForm: StateFlow<EditModelFormState> = _editingModelForm.asStateFlow()

    /**
     * Data class representing the state of the "Add New Model" form.
     */
    data class NewModelFormState(
        val name: String = "", // e.g., "gpt-3.5-turbo"
        val providerId: Long? = null,
        val active: Boolean = true,
        val displayName: String = "", // Optional, display name for UI
        val errorMessage: String? = null // For inline validation/API errors
    )

    /**
     * Data class representing the state of the "Edit Model" form.
     */
    data class EditModelFormState(
        val name: String = "", // e.g., "gpt-3.5-turbo"
        val providerId: Long? = null,
        val active: Boolean = true,
        val displayName: String = "", // Optional, display name for UI
        val errorMessage: String? = null // For inline validation/API errors
    )

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models (E4.S2) and providers (for selection dropdown).
     * Uses parZip for concurrent loading.
     */
    fun loadModelsAndProviders() {
        if (_modelsState.value.isLoading || _providersForSelection.value.isLoading) return // Prevent duplicate loading

        viewModelScope.launch(uiDispatcher) {
            _modelsState.value = UiState.Loading
            _providersForSelection.value = UiState.Loading

            parZip(
                ioDispatcher,
                { modelApi.getAllModels() },
                { providerApi.getAllProviders() }
            ) { modelsEither, providersEither ->
                modelsEither.fold(
                    ifLeft = { error ->
                        _modelsState.value = UiState.Error(error)
                        println("Error loading models: ${error.code} - ${error.message}")
                    },
                    ifRight = { models ->
                        _modelsState.value = UiState.Success(models)
                    }
                )

                providersEither.fold(
                    ifLeft = { error ->
                        _providersForSelection.value = UiState.Error(error)
                        println("Error loading providers for model selection: ${error.code} - ${error.message}")
                    },
                    ifRight = { providers ->
                        _providersForSelection.value = UiState.Success(providers)
                    }
                )
            }
        }
    }

    /**
     * Initiates the process of adding a new model by showing the form (E4.S1).
     */
    fun startAddingNewModel() {
        _isAddingNewModel.value = true
        _newModelForm.value = NewModelFormState(
            // Pre-select first provider if available, otherwise null
            providerId = _providersForSelection.value.dataOrNull?.firstOrNull()?.id
        )
    }

    /**
     * Cancels the new model addition process.
     */
    fun cancelAddingNewModel() {
        _isAddingNewModel.value = false
        _newModelForm.value = NewModelFormState() // Clear form
    }

    /**
     * Updates the name field in the new model form.
     */
    fun updateNewModelName(name: String) {
        _newModelForm.update { it.copy(name = name, errorMessage = null) }
    }

    /**
     * Updates the provider ID field in the new model form.
     */
    fun updateNewModelProviderId(providerId: Long?) {
        _newModelForm.update { it.copy(providerId = providerId, errorMessage = null) }
    }

    /**
     * Updates the active status field in the new model form.
     */
    fun updateNewModelActive(active: Boolean) {
        _newModelForm.update { it.copy(active = active, errorMessage = null) }
    }

    /**
     * Updates the display name field in the new model form.
     */
    fun updateNewModelDisplayName(displayName: String) {
        _newModelForm.update { it.copy(displayName = displayName, errorMessage = null) }
    }

    /**
     * Saves the new LLM model configuration to the backend (E4.S1).
     */
    fun addNewModel() {
        val form = _newModelForm.value
        if (form.name.isBlank() || form.providerId == null) {
            _newModelForm.update { it.copy(errorMessage = "Model Name and Provider must be selected.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val request = AddModelRequest(
                name = form.name.trim(),
                providerId = form.providerId, // Already validated as non-null
                type = LLMModelType.CHAT, // Default to CHAT type for now
                active = form.active,
                displayName = form.displayName.trim().takeIf { it.isNotBlank() } // Null if blank
            )
            val currentModels = _modelsState.value.dataOrNull

            modelApi.addModel(request)
                .fold(
                    ifLeft = { error ->
                        _newModelForm.update { it.copy(errorMessage = "Error adding model: ${error.message}") }
                        println("Error adding model: ${error.code} - ${error.message}")
                    },
                    ifRight = { newModel ->
                        // Add the new model to the list, maintaining the Success state (E4.S1)
                        if (currentModels != null) {
                            _modelsState.value = UiState.Success(currentModels + newModel)
                        } else {
                            // Fallback to full reload if state wasn't Success (shouldn't happen often)
                            loadModelsAndProviders()
                        }
                        cancelAddingNewModel() // Hide form and reset
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
        _editingModel.value = model
        _editingModelForm.value = EditModelFormState(
            name = model.name,
            providerId = model.providerId,
            active = model.active,
            displayName = model.displayName ?: "" // Convert null to empty string for UI field
        )
    }

    /**
     * Cancels the editing process for a model.
     */
    fun cancelEditingModel() {
        _editingModel.value = null
        _editingModelForm.value = EditModelFormState() // Clear form
    }

    /**
     * Updates the name field in the edit model form.
     */
    fun updateEditingModelName(name: String) {
        _editingModelForm.update { it.copy(name = name, errorMessage = null) }
    }

    /**
     * Updates the provider ID field in the edit model form.
     */
    fun updateEditingModelProviderId(providerId: Long?) {
        _editingModelForm.update { it.copy(providerId = providerId, errorMessage = null) }
    }

    /**
     * Updates the active status field in the edit model form.
     */
    fun updateEditingModelActive(active: Boolean) {
        _editingModelForm.update { it.copy(active = active, errorMessage = null) }
    }

    /**
     * Updates the display name field in the edit model form.
     */
    fun updateEditingModelDisplayName(displayName: String) {
        _editingModelForm.update { it.copy(displayName = displayName, errorMessage = null) }
    }

    /**
     * Saves the details of an edited model (E4.S3).
     */
    fun saveEditedModel() {
        val originalModel = _editingModel.value ?: return
        val form = _editingModelForm.value

        if (form.name.isBlank() || form.providerId == null) {
            _editingModelForm.update { it.copy(errorMessage = "Model Name and Provider must be selected.") }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            val updatedModel = originalModel.copy(
                name = form.name.trim(),
                providerId = form.providerId, // Already validated as non-null
                active = form.active,
                displayName = form.displayName.trim().takeIf { it.isNotBlank() }
            )
            val currentModels = _modelsState.value.dataOrNull

            modelApi.updateModel(updatedModel)
                .fold(
                    ifLeft = { error ->
                        _editingModelForm.update { it.copy(errorMessage = "Error updating model: ${error.message}") }
                        println("Error updating model: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Update the model in the list (E4.S3)
                        if (currentModels != null) {
                            _modelsState.value = UiState.Success(currentModels.map {
                                if (it.id == updatedModel.id) updatedModel else it
                            })
                        } else {
                            loadModelsAndProviders() // Fallback to full reload
                        }
                        // Keep editing state active, but clear error messages related to this save
                        _editingModelForm.update { it.copy(errorMessage = null) }
                        // Also update the _editingModel itself to reflect changes in the UI
                        _editingModel.value = updatedModel
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
        viewModelScope.launch(uiDispatcher) {
            val currentModels = _modelsState.value.dataOrNull
            if (currentModels == null) {
                println("Cannot delete model: model list is not in Success state.")
                return@launch
            }

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
                        _modelsState.value = UiState.Success(currentModels.filter { it.id != modelId })
                        // If the deleted model was being edited, clear the editing state
                        if (_editingModel.value?.id == modelId) {
                            cancelEditingModel()
                        }
                    }
                )
        }
    }
}