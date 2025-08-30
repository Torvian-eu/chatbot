package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMModelType
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
 * This ViewModel is completely type-agnostic and supports any ModelSettings type that has
 * corresponding form state implementations in SettingsFormState.
 *
 * It handles:
 * - Loading and displaying a list of available LLM models (for selection).
 * - Loading and displaying a list of settings profiles associated with a selected model (E4.S5).
 * - Managing the state for adding new settings profiles (E4.S5).
 * - Managing the state for editing existing settings profiles (E4.S6).
 * - Deleting settings profiles (E4.S5).
 * - Communicating with the backend via [SettingsApi] and [ModelApi].
 */
class SettingsConfigViewModel(
    private val settingsApi: SettingsApi,
    private val modelApi: ModelApi,
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
     * Includes loading, success with data, or error states. Only supported ModelSettings types are included.
     */
    val settingsState: StateFlow<UiState<ApiError, List<ModelSettings>>> = _settingsState.asStateFlow()

    private val _isAddingNewSettings = MutableStateFlow(false)

    /**
     * Controls the visibility of the "Add New Settings Profile" form (E4.S5).
     */
    val isAddingNewSettings: StateFlow<Boolean> = _isAddingNewSettings.asStateFlow()

    private val _settingsForm = MutableStateFlow(getDefaultNewFormState())

    /**
     * Holds the current form state for settings - can be for adding new settings or editing existing ones.
     * Check the form's mode property to determine if it's for creation or editing.
     */
    val settingsForm: StateFlow<SettingsFormState> = _settingsForm.asStateFlow()

    private val _editingSettings = MutableStateFlow<ModelSettings?>(null)

    /**
     * The settings profile currently being edited (E4.S6). Null if no settings are being edited.
     */
    val editingSettings: StateFlow<ModelSettings?> = _editingSettings.asStateFlow()

    private val _selectedSettingsType = MutableStateFlow(getDefaultSettingsType())

    /**
     * The type of settings to create when adding new settings profiles.
     * This determines which form variant will be used for new settings creation.
     */
    val selectedSettingsType: StateFlow<LLMModelType> = _selectedSettingsType.asStateFlow()

    // --- Private Helper Functions ---

    private fun getDefaultSettingsType(): LLMModelType {
        return getSupportedSettingsTypes().first()
    }

    private fun getDefaultNewFormState(): SettingsFormState {
        return createEmptyNewSettingsForm(getDefaultSettingsType())
    }

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models to populate the model selection dropdown.
     */
    fun loadModels() {
        if (_modelsForSelection.value.isLoading) return
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
                        if (_selectedModelId.value == null && models.isNotEmpty()) {
                            selectModel(models.first().id)
                        }
                    }
                )
        }
    }

    /**
     * Selects an LLM model and loads its associated settings profiles.
     */
    fun selectModel(modelId: Long?) {
        if (_selectedModelId.value == modelId && _settingsState.value.isSuccess) return

        _selectedModelId.value = modelId
        _settingsState.value = UiState.Idle
        cancelFormOperation()

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
                        // Filter to only supported types
                        val supportedSettings = settingsList.filter { isModelSettingsSupported(it) }
                        _settingsState.value = UiState.Success(supportedSettings)
                        if (supportedSettings.size != settingsList.size) {
                            println("Warning: Found unsupported ModelSettings types for model $modelId. Only supported types are displayed.")
                        }
                    }
                )
        }
    }

    /**
     * Sets the type of settings to create when adding new settings.
     */
    fun selectSettingsType(modelType: LLMModelType) {
        _selectedSettingsType.value = modelType
        if (_isAddingNewSettings.value) {
            _settingsForm.value = createEmptyNewSettingsForm(modelType)
        }
    }

    /**
     * Initiates the process of adding a new settings profile by showing the form.
     */
    fun startAddingNewSettings() {
        _isAddingNewSettings.value = true
        _editingSettings.value = null
        _settingsForm.value = createEmptyNewSettingsForm(_selectedSettingsType.value)
    }

    /**
     * Cancels any form operation (adding new or editing existing settings).
     */
    fun cancelFormOperation() {
        _isAddingNewSettings.value = false
        _editingSettings.value = null
        _settingsForm.value = createEmptyNewSettingsForm(_selectedSettingsType.value)
    }

    /**
     * Updates any field in the current settings form.
     */
    fun updateSettingsForm(update: (SettingsFormState) -> SettingsFormState) {
        _settingsForm.update { update(it).withError(null) }
    }

    /**
     * Saves the current form - either creates new settings or updates existing ones based on the form mode.
     */
    fun saveSettings() {
        val modelId = _selectedModelId.value
        if (modelId == null) {
            _settingsForm.update { it.withError("No model selected for settings.") }
            return
        }

        val form = _settingsForm.value
        val validationError = form.validate()
        if (validationError != null) {
            _settingsForm.update { it.withError(validationError) }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            try {
                when (form.mode) {
                    FormMode.NEW -> addNewSettings(form, modelId)
                    FormMode.EDIT -> updateExistingSettings(form, modelId)
                }
            } catch (e: Exception) {
                _settingsForm.update { it.withError(e.message) }
                println("Error saving settings: ${e.message}")
            }
        }
    }

    private suspend fun addNewSettings(form: SettingsFormState, modelId: Long) {
        val newSettings = form.toModelSettings(0L, modelId)
        val currentSettings = _settingsState.value.dataOrNull

        settingsApi.addModelSettings(newSettings)
            .fold(
                ifLeft = { error ->
                    _settingsForm.update { it.withError("Error adding settings: ${error.message}") }
                    println("Error adding settings: ${error.code} - ${error.message}")
                },
                ifRight = { createdSettings ->
                    if (currentSettings != null) {
                        _settingsState.value = UiState.Success(currentSettings + createdSettings)
                    } else {
                        selectModel(modelId)
                    }
                    cancelFormOperation()
                }
            )
    }

    private suspend fun updateExistingSettings(form: SettingsFormState, modelId: Long) {
        val originalSettings = _editingSettings.value ?: return
        val updatedSettings = form.toModelSettings(originalSettings.id, originalSettings.modelId)
        val currentSettings = _settingsState.value.dataOrNull

        settingsApi.updateSettings(updatedSettings)
            .fold(
                ifLeft = { error ->
                    _settingsForm.update { it.withError("Error updating settings: ${error.message}") }
                    println("Error updating settings: ${error.code} - ${error.message}")
                },
                ifRight = {
                    if (currentSettings != null) {
                        val updatedList = currentSettings.map {
                            if (it.id == updatedSettings.id) updatedSettings else it
                        }
                        _settingsState.value = UiState.Success(updatedList)
                    } else {
                        selectModel(originalSettings.modelId)
                    }
                    _settingsForm.update { it.withError(null) }
                    _editingSettings.value = updatedSettings
                }
            )
    }

    /**
     * Initiates the editing process for an existing settings profile.
     */
    fun startEditingSettings(settings: ModelSettings) {
        if (!isModelSettingsSupported(settings)) {
            println("Cannot edit: Settings with ID ${settings.id} is of unsupported type ${settings::class.simpleName}.")
            return
        }

        _isAddingNewSettings.value = false
        _editingSettings.value = settings
        _settingsForm.value = settings.toEditFormState()
    }

    /**
     * Deletes a specific LLM settings profile.
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
                        println("Error deleting settings: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        _settingsState.value = UiState.Success(currentSettings.filter { it.id != settingsId })
                        if (_editingSettings.value?.id == settingsId) {
                            cancelFormOperation()
                        }
                    }
                )
        }
    }

    /**
     * Gets the available settings types that can be created for the currently selected model.
     */
    fun getAvailableSettingsTypes(): List<LLMModelType> {
        val selectedModel = _modelsForSelection.value.dataOrNull?.find { it.id == _selectedModelId.value }

        // If we know the model type, filter available types accordingly
        // Otherwise, return all supported types
        return when (selectedModel?.type) {
            LLMModelType.CHAT -> getSupportedSettingsTypes().filter { it == LLMModelType.CHAT }
            LLMModelType.EMBEDDING -> getSupportedSettingsTypes().filter { it == LLMModelType.EMBEDDING }
            else -> getSupportedSettingsTypes()
        }
    }
}
