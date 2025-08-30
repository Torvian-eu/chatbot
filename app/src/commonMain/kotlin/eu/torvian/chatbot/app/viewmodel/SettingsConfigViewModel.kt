package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.app.domain.contracts.NewSettingsFormState
import eu.torvian.chatbot.app.domain.contracts.EditSettingsFormState
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Manages the UI state and logic for configuring LLM Model Settings Profiles (E4.S5, E4.S6).
 *
 * This ViewModel currently focuses on managing settings for [ChatModelSettings] only.
 *
 * It handles:
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
 *                         Only [ChatModelSettings] are displayed and manageable by this ViewModel.
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

    private val _settingsState = MutableStateFlow<UiState<ApiError, List<ChatModelSettings>>>(UiState.Idle)

    /**
     * The state of the list of settings profiles for the [selectedModelId] (E4.S5).
     * Includes loading, success with data, or error states. Only [ChatModelSettings] are included.
     */
    val settingsState: StateFlow<UiState<ApiError, List<ChatModelSettings>>> = _settingsState.asStateFlow()

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

    private val _editingSettings = MutableStateFlow<ChatModelSettings?>(null) // Now specifically ChatModelSettings

    /**
     * The settings profile currently being edited (E4.S6). Null if no settings are being edited.
     */
    val editingSettings: StateFlow<ChatModelSettings?> = _editingSettings.asStateFlow()

    private val _editingSettingsForm = MutableStateFlow(EditSettingsFormState())

    /**
     * Holds the data for the "Edit Settings Profile" form (E4.S6).
     */
    val editingSettingsForm: StateFlow<EditSettingsFormState> = _editingSettingsForm.asStateFlow()

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
                    ifRight = { settingsList ->
                        // Filter to only ChatModelSettings as this ViewModel currently only supports them
                        val chatSettingsList = settingsList.filterIsInstance<ChatModelSettings>()
                        _settingsState.value = UiState.Success(chatSettingsList)
                        if (chatSettingsList.size != settingsList.size) {
                            println("Warning: Found non-ChatModelSettings for model $modelId. These are not supported by this UI.")
                        }
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

    fun updateNewSettingsTopP(topP: String) {
        _newSettingsForm.update { it.copy(topP = topP, errorMessage = null) }
    }

    fun updateNewSettingsTopK(topK: String) {
        _newSettingsForm.update { it.copy(topK = topK, errorMessage = null) }
    }

    fun updateNewSettingsStopSequences(stopSequences: String) {
        _newSettingsForm.update { it.copy(stopSequences = stopSequences, errorMessage = null) }
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

        // Basic validation for name (constructor will also validate, but early UI feedback is good)
        if (form.name.isBlank()) {
            _newSettingsForm.update { it.copy(errorMessage = "Settings profile name cannot be empty.") }
            return
        }

        // Parse optional numeric/list fields from String inputs
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
        val topPFloat = form.topP.toFloatOrNull().also {
            if (form.topP.isNotBlank() && it == null) {
                _newSettingsForm.update { s -> s.copy(errorMessage = "Top P must be a number.") }
                return
            }
        }
        val topKInt = form.topK.toIntOrNull().also {
            if (form.topK.isNotBlank() && it == null) {
                _newSettingsForm.update { s -> s.copy(errorMessage = "Top K must be an integer.") }
                return
            }
        }
        val stopSequencesList = form.stopSequences.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }

        viewModelScope.launch(uiDispatcher) {
            val customParams = form.customParamsJson.trim().takeIf { it.isNotBlank() }?.let { jsonString ->
                try {
                    Json.decodeFromString<JsonObject>(jsonString)
                } catch (_: Exception) {
                    _newSettingsForm.update { s -> s.copy(errorMessage = "Invalid JSON format in custom parameters.") }
                    return@launch
                }
            }

            try {
                // Construct the specific ChatModelSettings object
                val chatSettings = ChatModelSettings(
                    id = 0L, // ID will be assigned by the backend
                    modelId = modelId,
                    name = form.name.trim(),
                    systemMessage = form.systemMessage.trim().takeIf { it.isNotBlank() },
                    temperature = temperatureFloat,
                    maxTokens = maxTokensInt,
                    topP = topPFloat,
                    topK = topKInt,
                    stopSequences = stopSequencesList,
                    customParams = customParams
                )

                val currentSettings = _settingsState.value.dataOrNull

                settingsApi.addModelSettings(chatSettings) // Pass the specific ChatModelSettings
                    .fold(
                        ifLeft = { error ->
                            _newSettingsForm.update { it.copy(errorMessage = "Error adding settings: ${error.message}") }
                            println("Error adding settings: ${error.code} - ${error.message}")
                        },
                        ifRight = { newSettings ->
                            // Add the new settings profile to the list, maintaining the Success state
                            // Ensure it's a ChatModelSettings before adding to the list
                            if (currentSettings != null && newSettings is ChatModelSettings) {
                                _settingsState.value = UiState.Success(currentSettings + newSettings)
                            } else {
                                // Fallback to full reload if state wasn't Success or type mismatch (shouldn't happen)
                                selectModel(modelId) // Reloads settings for the selected model
                            }
                            cancelAddingNewSettings() // Hide form and reset
                        }
                    )
            } catch (e: IllegalArgumentException) {
                // Catch validation errors from ChatModelSettings constructor
                _newSettingsForm.update { it.copy(errorMessage = e.message) }
                println("Client-side validation failed for new settings: ${e.message}")
            }
        }
    }

    /**
     * Initiates the editing process for an existing settings profile (E4.S6).
     *
     * @param settings The [ModelSettings] to be edited. Must be a [ChatModelSettings].
     */
    fun startEditingSettings(settings: ModelSettings) {
        if (settings !is ChatModelSettings) {
            println("Cannot edit: Settings with ID ${settings.id} is not a ChatModelSettings. This ViewModel only supports ChatModelSettings.")
            _editingSettings.value = null
            _editingSettingsForm.value = EditSettingsFormState(errorMessage = "Unsupported settings type for editing.")
            return
        }

        _editingSettings.value = settings // Store the ChatModelSettings instance
        _editingSettingsForm.value = EditSettingsFormState(
            name = settings.name,
            systemMessage = settings.systemMessage ?: "",
            temperature = settings.temperature?.toString() ?: "",
            maxTokens = settings.maxTokens?.toString() ?: "",
            topP = settings.topP?.toString() ?: "",
            topK = settings.topK?.toString() ?: "",
            stopSequences = settings.stopSequences?.joinToString(",") ?: "",
            customParamsJson = settings.customParams?.let { Json.encodeToString(it) } ?: ""
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

    fun updateEditingSettingsTopP(topP: String) {
        _editingSettingsForm.update { it.copy(topP = topP, errorMessage = null) }
    }

    fun updateEditingSettingsTopK(topK: String) {
        _editingSettingsForm.update { it.copy(topK = topK, errorMessage = null) }
    }

    fun updateEditingSettingsStopSequences(stopSequences: String) {
        _editingSettingsForm.update { it.copy(stopSequences = stopSequences, errorMessage = null) }
    }

    fun updateEditingSettingsCustomParamsJson(json: String) {
        _editingSettingsForm.update { it.copy(customParamsJson = json, errorMessage = null) }
    }

    /**
     * Saves the details of an edited settings profile (E4.S6).
     */
    fun saveEditedSettings() {
        val originalSettings = _editingSettings.value ?: return // Must be ChatModelSettings
        val form = _editingSettingsForm.value

        // Basic validation for name (constructor will also validate)
        if (form.name.isBlank()) {
            _editingSettingsForm.update { it.copy(errorMessage = "Settings profile name cannot be empty.") }
            return
        }

        // Parse optional numeric/list fields from String inputs
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
        val topPFloat = form.topP.toFloatOrNull().also {
            if (form.topP.isNotBlank() && it == null) {
                _editingSettingsForm.update { s -> s.copy(errorMessage = "Top P must be a number.") }
                return
            }
        }
        val topKInt = form.topK.toIntOrNull().also {
            if (form.topK.isNotBlank() && it == null) {
                _editingSettingsForm.update { s -> s.copy(errorMessage = "Top K must be an integer.") }
                return
            }
        }
        val stopSequencesList = form.stopSequences.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }

        viewModelScope.launch(uiDispatcher) {
            val customParams = form.customParamsJson.trim().takeIf { it.isNotBlank() }?.let { jsonString ->
                try {
                    Json.decodeFromString<JsonObject>(jsonString)
                } catch (_: Exception) {
                    _editingSettingsForm.update { s -> s.copy(errorMessage = "Invalid JSON format in custom parameters.") }
                    return@launch
                }
            }

            try {
                // Create an updated ChatModelSettings object
                val updatedChatSettings = originalSettings.copy(
                    name = form.name.trim(),
                    systemMessage = form.systemMessage.trim().takeIf { it.isNotBlank() },
                    temperature = temperatureFloat,
                    maxTokens = maxTokensInt,
                    topP = topPFloat,
                    topK = topKInt,
                    stopSequences = stopSequencesList,
                    customParams = customParams
                )
                val currentSettings = _settingsState.value.dataOrNull

                settingsApi.updateSettings(updatedChatSettings) // Pass the specific ChatModelSettings
                    .fold(
                        ifLeft = { error ->
                            _editingSettingsForm.update { it.copy(errorMessage = "Error updating settings: ${error.message}") }
                            println("Error updating settings: ${error.code} - ${error.message}")
                        },
                        ifRight = {
                            // Update the settings in the list (E4.S6)
                            if (currentSettings != null) {
                                val updatedList = currentSettings.map {
                                    if (it.id == updatedChatSettings.id) updatedChatSettings else it
                                }
                                _settingsState.value = UiState.Success(updatedList)
                            } else {
                                // Fallback to full reload if state wasn't Success
                                selectModel(originalSettings.modelId) // Reloads settings for the selected model
                            }
                            // Keep editing state active, but clear error messages related to this save
                            _editingSettingsForm.update { it.copy(errorMessage = null) }
                            // Also update the _editingSettings itself to reflect changes in the UI
                            _editingSettings.value = updatedChatSettings
                        }
                    )
            } catch (e: IllegalArgumentException) {
                // Catch validation errors from ChatModelSettings constructor
                _editingSettingsForm.update { it.copy(errorMessage = e.message) }
                println("Client-side validation failed for edited settings: ${e.message}")
            }
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

