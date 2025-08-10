package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.AddModelSettingsRequest
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for configuring LLM Model Settings Profiles (E4.S5, E4.S6).
 *
 * This ViewModel handles:
 * - Loading and displaying a list of available LLM models (for selection).
 * - Loading and displaying a list of settings profiles associated with a selected model (E4.S5).
 * - Managing the state for adding new settings profiles (E4.S5).
 * - Managing the state for editing existing settings profiles (E4.S6).
 * - Deleting settings profiles (E4.S5).
 * - Communicating with the backend via [SettingsApi] and [ModelApi].
 *
 * @constructor
 * @param settingsApi The API client for LLM Model Settings-related operations.
 * @param modelApi The API client for LLM Model-related operations.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property modelsForSelection The state of the list of LLM models available for selection (E4.S5).
 * @property selectedModelId The ID of the currently selected LLM model (E4.S5).
 * @property settingsState The state of the list of settings profiles for the [selectedModelId] (E4.S5).
 * @property isAddingNewSettings UI state indicating if the "Add New Settings Profile" form is visible (E4.S5).
 * @property newSettingsForm The state of the "Add New Settings Profile" form (E4.S5).
 * @property editingSettings The settings profile currently being edited (E4.S6). Null if no settings are being edited.
 * @property editingSettingsForm The state of the "Edit Settings Profile" form (E4.S6).
 */
class SettingsConfigViewModel(
    private val settingsApi: SettingsApi,
    private val modelApi: ModelApi, // Needed to populate model dropdown for settings config
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Observable State for Compose UI ---

    private val _modelsForSelection = MutableStateFlow<UiState<ApiError, List<LLMModel>>>(UiState.Idle)

    /**
     * The state of the list of LLM models available for selection when managing settings.
     * This list is used to select which model's settings to view/manage.
     */
    val modelsForSelection: StateFlow<UiState<ApiError, List<LLMModel>>> = _modelsForSelection.asStateFlow()

    private val _selectedModelId = MutableStateFlow<Long?>(null)

    /**
     * The ID of the currently selected LLM model. Settings profiles will be loaded for this model.
     * If null, no model is selected and no settings are displayed.
     */
    val selectedModelId: StateFlow<Long?> = _selectedModelId.asStateFlow()

    private val _settingsState = MutableStateFlow<UiState<ApiError, List<ModelSettings>>>(UiState.Idle)

    /**
     * The state of the list of settings profiles for the [selectedModelId] (E4.S5).
     * Includes loading, success with data, or error states.
     */
    val settingsState: StateFlow<UiState<ApiError, List<ModelSettings>>> = _settingsState.asStateFlow()

    private val _isAddingNewSettings = MutableStateFlow(false)

    /**
     * Controls the visibility of the "Add New Settings Profile" form (E4.S5).
     */
    val isAddingNewSettings: StateFlow<Boolean> = _isAddingNewSettings.asStateFlow()

    private val _newSettingsForm = MutableStateFlow(NewSettingsFormState())

    /**
     * Holds the data for the "Add New Settings Profile" form (E4.S5).
     * Note: Model ID is determined by `_selectedModelId`.
     */
    val newSettingsForm: StateFlow<NewSettingsFormState> = _newSettingsForm.asStateFlow()

    private val _editingSettings = MutableStateFlow<ModelSettings?>(null)

    /**
     * The settings profile currently being edited (E4.S6). Null if no settings are being edited.
     */
    val editingSettings: StateFlow<ModelSettings?> = _editingSettings.asStateFlow()

    private val _editingSettingsForm = MutableStateFlow(EditSettingsFormState())

    /**
     * Holds the data for the "Edit Settings Profile" form (E4.S6).
     */
    val editingSettingsForm: StateFlow<EditSettingsFormState> = _editingSettingsForm.asStateFlow()

    /**
     * Data class representing the state of the "Add New Settings Profile" form.
     */
    data class NewSettingsFormState(
        val name: String = "",
        val systemMessage: String = "",
        val temperature: String = "", // String for UI input, will be parsed to Float
        val maxTokens: String = "",   // String for UI input, will be parsed to Int
        val customParamsJson: String = "",
        val errorMessage: String? = null // For inline validation/API errors
    )

    /**
     * Data class representing the state of the "Edit Settings Profile" form.
     */
    data class EditSettingsFormState(
        val name: String = "",
        val systemMessage: String = "",
        val temperature: String = "", // String for UI input
        val maxTokens: String = "",   // String for UI input
        val customParamsJson: String = "",
        val errorMessage: String? = null // For inline validation/API errors
    )

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models (E4.S2) to populate the model selection dropdown.
     */
    fun loadModels() {
        if (_modelsForSelection.value.isLoading) return // Prevent duplicate loading
        viewModelScope.launch(uiDispatcher) {
            _modelsForSelection.value = UiState.Loading
            modelApi.getAllModels()
                .fold(
                    ifLeft = { error ->
                        _modelsForSelection.value = UiState.Error(error)
                        println("Error loading models for settings selection: ${error.code} - ${error.message}")
                    },
                    ifRight = { models ->
                        _modelsForSelection.value = UiState.Success(models)
                        // Optionally auto-select the first model if available and nothing is selected
                        if (_selectedModelId.value == null && models.isNotEmpty()) {
                            selectModel(models.first().id)
                        }
                    }
                )
        }
    }

    /**
     * Selects an LLM model and loads its associated settings profiles (E4.S5).
     *
     * @param modelId The ID of the model whose settings to load, or null to clear selection.
     */
    fun selectModel(modelId: Long?) {
        if (_selectedModelId.value == modelId && _settingsState.value.isSuccess) return // Already selected and loaded

        _selectedModelId.value = modelId
        _settingsState.value = UiState.Idle // Reset settings state when model selection changes or clears
        cancelAddingNewSettings() // Hide new settings form
        cancelEditingSettings() // Hide edit settings form

        if (modelId == null) return

        viewModelScope.launch(uiDispatcher) {
            _settingsState.value = UiState.Loading
            settingsApi.getSettingsByModelId(modelId)
                .fold(
                    ifLeft = { error ->
                        _settingsState.value = UiState.Error(error)
                        println("Error loading settings for model $modelId: ${error.code} - ${error.message}")
                    },
                    ifRight = { settings ->
                        _settingsState.value = UiState.Success(settings)
                    }
                )
        }
    }

    /**
     * Initiates the process of adding a new settings profile by showing the form (E4.S5).
     */
    fun startAddingNewSettings() {
        _isAddingNewSettings.value = true
        _newSettingsForm.value = NewSettingsFormState() // Reset form
    }

    /**
     * Cancels the new settings profile addition process.
     */
    fun cancelAddingNewSettings() {
        _isAddingNewSettings.value = false
        _newSettingsForm.value = NewSettingsFormState() // Clear form
    }

    // New settings form input handlers
    fun updateNewSettingsName(name: String) {
        _newSettingsForm.update { it.copy(name = name, errorMessage = null) }
    }

    fun updateNewSettingsSystemMessage(message: String) {
        _newSettingsForm.update { it.copy(systemMessage = message, errorMessage = null) }
    }

    fun updateNewSettingsTemperature(temperature: String) {
        _newSettingsForm.update { it.copy(temperature = temperature, errorMessage = null) }
    }

    fun updateNewSettingsMaxTokens(maxTokens: String) {
        _newSettingsForm.update { it.copy(maxTokens = maxTokens, errorMessage = null) }
    }

    fun updateNewSettingsCustomParamsJson(json: String) {
        _newSettingsForm.update { it.copy(customParamsJson = json, errorMessage = null) }
    }

    /**
     * Saves the new LLM settings profile to the backend (E4.S5).
     */
    fun addNewSettings() {
        val modelId = _selectedModelId.value
        if (modelId == null) {
            _newSettingsForm.update { it.copy(errorMessage = "No model selected for settings.") }
            return
        }
        val form = _newSettingsForm.value
        if (form.name.isBlank()) {
            _newSettingsForm.update { it.copy(errorMessage = "Settings profile name cannot be empty.") }
            return
        }

        // Parse optional numeric fields from String inputs
        val temperatureFloat = form.temperature.toFloatOrNull().also {
            if (form.temperature.isNotBlank() && it == null) {
                _newSettingsForm.update { s -> s.copy(errorMessage = "Temperature must be a number.") }
                return
            }
        }
        val maxTokensInt = form.maxTokens.toIntOrNull().also {
            if (form.maxTokens.isNotBlank() && it == null) {
                _newSettingsForm.update { s -> s.copy(errorMessage = "Max Tokens must be an integer.") }
                return
            }
        }

        viewModelScope.launch(uiDispatcher) {
            val request = AddModelSettingsRequest(
                name = form.name.trim(),
                systemMessage = form.systemMessage.trim().takeIf { it.isNotBlank() },
                temperature = temperatureFloat,
                maxTokens = maxTokensInt,
                customParamsJson = form.customParamsJson.trim().takeIf { it.isNotBlank() }
            )
            val currentSettings = _settingsState.value.dataOrNull

            settingsApi.addModelSettings(modelId, request)
                .fold(
                    ifLeft = { error ->
                        _newSettingsForm.update { it.copy(errorMessage = "Error adding settings: ${error.message}") }
                        println("Error adding settings: ${error.code} - ${error.message}")
                    },
                    ifRight = { newSettings ->
                        // Add the new settings profile to the list, maintaining the Success state (E4.S5)
                        if (currentSettings != null) {
                            _settingsState.value = UiState.Success(currentSettings + newSettings)
                        } else {
                            // Fallback to full reload if state wasn't Success
                            selectModel(modelId) // Reloads settings for the selected model
                        }
                        cancelAddingNewSettings() // Hide form and reset
                    }
                )
        }
    }

    /**
     * Initiates the editing process for an existing settings profile (E4.S6).
     *
     * @param settings The [ModelSettings] to be edited.
     */
    fun startEditingSettings(settings: ModelSettings) {
        _editingSettings.value = settings
        _editingSettingsForm.value = EditSettingsFormState(
            name = settings.name,
            systemMessage = settings.systemMessage ?: "",
            temperature = settings.temperature?.toString() ?: "",
            maxTokens = settings.maxTokens?.toString() ?: "",
            customParamsJson = settings.customParamsJson ?: ""
        )
    }

    /**
     * Cancels the editing process for a settings profile.
     */
    fun cancelEditingSettings() {
        _editingSettings.value = null
        _editingSettingsForm.value = EditSettingsFormState() // Clear form
    }

    // Editing settings form input handlers
    fun updateEditingSettingsName(name: String) {
        _editingSettingsForm.update { it.copy(name = name, errorMessage = null) }
    }

    fun updateEditingSettingsSystemMessage(message: String) {
        _editingSettingsForm.update { it.copy(systemMessage = message, errorMessage = null) }
    }

    fun updateEditingSettingsTemperature(temperature: String) {
        _editingSettingsForm.update { it.copy(temperature = temperature, errorMessage = null) }
    }

    fun updateEditingSettingsMaxTokens(maxTokens: String) {
        _editingSettingsForm.update { it.copy(maxTokens = maxTokens, errorMessage = null) }
    }

    fun updateEditingSettingsCustomParamsJson(json: String) {
        _editingSettingsForm.update { it.copy(customParamsJson = json, errorMessage = null) }
    }

    /**
     * Saves the details of an edited settings profile (E4.S6).
     */
    fun saveEditedSettings() {
        val originalSettings = _editingSettings.value ?: return
        val form = _editingSettingsForm.value

        if (form.name.isBlank()) {
            _editingSettingsForm.update { it.copy(errorMessage = "Settings profile name cannot be empty.") }
            return
        }

        // Parse optional numeric fields from String inputs
        val temperatureFloat = form.temperature.toFloatOrNull().also {
            if (form.temperature.isNotBlank() && it == null) {
                _editingSettingsForm.update { s -> s.copy(errorMessage = "Temperature must be a number.") }
                return
            }
        }
        val maxTokensInt = form.maxTokens.toIntOrNull().also {
            if (form.maxTokens.isNotBlank() && it == null) {
                _editingSettingsForm.update { s -> s.copy(errorMessage = "Max Tokens must be an integer.") }
                return
            }
        }

        viewModelScope.launch(uiDispatcher) {
            val updatedSettings = originalSettings.copy(
                name = form.name.trim(),
                systemMessage = form.systemMessage.trim().takeIf { it.isNotBlank() },
                temperature = temperatureFloat,
                maxTokens = maxTokensInt,
                customParamsJson = form.customParamsJson.trim().takeIf { it.isNotBlank() }
            )
            val currentSettings = _settingsState.value.dataOrNull

            settingsApi.updateSettings(updatedSettings)
                .fold(
                    ifLeft = { error ->
                        _editingSettingsForm.update { it.copy(errorMessage = "Error updating settings: ${error.message}") }
                        println("Error updating settings: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Update the settings in the list (E4.S6)
                        if (currentSettings != null) {
                            _settingsState.value = UiState.Success(currentSettings.map {
                                if (it.id == updatedSettings.id) updatedSettings else it
                            })
                        } else {
                            // Fallback to full reload if state wasn't Success
                            selectModel(originalSettings.modelId) // Reloads settings for the selected model
                        }
                        // Keep editing state active, but clear error messages related to this save
                        _editingSettingsForm.update { it.copy(errorMessage = null) }
                        // Also update the _editingSettings itself to reflect changes in the UI
                        _editingSettings.value = updatedSettings
                    }
                )
        }
    }

    /**
     * Deletes a specific LLM settings profile (E4.S5).
     *
     * @param settingsId The ID of the settings profile to delete.
     */
    fun deleteSettings(settingsId: Long) {
        val modelId = _selectedModelId.value
        if (modelId == null) {
            println("Cannot delete settings: No model selected.")
            return
        }
        viewModelScope.launch(uiDispatcher) {
            val currentSettings = _settingsState.value.dataOrNull
            if (currentSettings == null) {
                println("Cannot delete settings: settings list is not in Success state.")
                return@launch
            }

            settingsApi.deleteSettings(settingsId)
                .fold(
                    ifLeft = { error ->
                        // Present the error to the user.
                        // (No specific RESOURCE_IN_USE for settings in E4.S5, but useful to handle generally)
                        println("Error deleting settings: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        // Remove the deleted settings from the list (E4.S5)
                        _settingsState.value = UiState.Success(currentSettings.filter { it.id != settingsId })
                        // If the deleted settings were being edited, clear the editing state
                        if (_editingSettings.value?.id == settingsId) {
                            cancelEditingSettings()
                        }
                    }
                )
        }
    }
}