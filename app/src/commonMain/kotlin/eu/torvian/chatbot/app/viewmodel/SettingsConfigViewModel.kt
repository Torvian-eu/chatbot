package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.ApiError
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
 *
 * @constructor
 * @param settingsApi The API client for Settings-related operations.
 * @param modelApi The API client for Model-related operations (needed for model selection).
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property settingsConfigState The state containing both models and settings data for unified state management.
 * @property selectedModelId The ID of the currently selected LLM model. Settings profiles will be loaded for this model.
 * @property dialogState The current dialog state for the settings tab.
 */
class SettingsConfigViewModel(
    private val settingsApi: SettingsApi,
    private val modelApi: ModelApi,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    // --- Private State Properties ---

    private val _settingsConfigState = MutableStateFlow<UiState<ApiError, SettingsConfigData>>(UiState.Idle)
    private val _selectedModelId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<SettingsDialogState>(SettingsDialogState.None)

    // --- Public State Properties ---

    /**
     * The state containing both models and settings data for unified state management.
     */
    val settingsConfigState: StateFlow<UiState<ApiError, SettingsConfigData>> = _settingsConfigState.asStateFlow()

    /**
     * The ID of the currently selected LLM model. Settings profiles will be loaded for this model.
     * If null, no model is selected and no settings are displayed.
     */
    val selectedModelId: StateFlow<Long?> = _selectedModelId.asStateFlow()

    /**
     * The current dialog state for the settings tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<SettingsDialogState> = _dialogState.asStateFlow()

    // --- Helper Properties ---

    /**
     * Helper property to get the current config data if in success state, null otherwise.
     */
    private val currentConfigData: SettingsConfigData?
        get() = _settingsConfigState.value.dataOrNull

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models to populate the model selection dropdown.
     */
    fun loadModels() {
        if (_settingsConfigState.value.isLoading) return
        viewModelScope.launch(uiDispatcher) {
            _settingsConfigState.value = UiState.Loading
            modelApi.getAllModels()
                .fold(
                    ifLeft = { error ->
                        _settingsConfigState.value = UiState.Error(error)
                        println("Error loading models for settings selection: ${error.code} - ${error.message}")
                    },
                    ifRight = { models ->
                        val configData = SettingsConfigData(
                            models = models,
                            settings = emptyList()
                        )
                        _settingsConfigState.value = UiState.Success(configData)
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
        if (_selectedModelId.value == modelId && currentConfigData?.settings?.isNotEmpty() == true) return

        _selectedModelId.value = modelId
        cancelDialog()

        if (modelId == null) {
            // Clear settings when no model is selected
            updateSettingsInState { emptyList() }
            return
        }

        viewModelScope.launch(uiDispatcher) {
            settingsApi.getSettingsByModelId(modelId)
                .fold(
                    ifLeft = { error ->
                        _settingsConfigState.value = UiState.Error(error)
                        println("Error loading settings for model $modelId: ${error.code} - ${error.message}")
                    },
                    ifRight = { settingsList ->
                        // Filter to only supported types
                        val supportedSettings = settingsList.filter { isModelSettingsSupported(it) }
                        updateSettingsInState { supportedSettings }
                        if (supportedSettings.size != settingsList.size) {
                            println("Warning: Found unsupported ModelSettings types for model $modelId. Only supported types are displayed.")
                        }
                    }
                )
        }
    }

    /**
     * Initiates the process of adding a new settings profile by showing the form.
     */
    fun startAddingNewSettings() {
        val availableTypes = getSupportedSettingsTypes()
        val defaultType = availableTypes.first()

        _dialogState.value = SettingsDialogState.AddNewSettings(
            formState = createEmptyNewSettingsForm(defaultType),
            availableSettingsTypes = availableTypes,
            selectedSettingsType = defaultType
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

        _dialogState.value = SettingsDialogState.EditSettings(
            settings = settings,
            formState = settings.toEditFormState()
        )
    }

    /**
     * Initiates the deletion process for a settings profile by showing the confirmation dialog.
     */
    fun startDeletingSettings(settings: ModelSettings) {
        _dialogState.value = SettingsDialogState.DeleteSettings(settings)
    }

    /**
     * Sets the type of settings to create when adding new settings.
     */
    fun selectSettingsType(modelType: LLMModelType) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is SettingsDialogState.AddNewSettings -> dialogState.copy(
                    selectedSettingsType = modelType,
                    formState = createEmptyNewSettingsForm(modelType)
                )

                else -> dialogState
            }
        }
    }

    /**
     * Updates any field in the current settings form.
     */
    fun updateSettingsForm(update: (SettingsFormState) -> SettingsFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is SettingsDialogState.AddNewSettings -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                is SettingsDialogState.EditSettings -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                else -> dialogState
            }
        }
    }

    /**
     * Saves the current form - either creates new settings or updates existing ones based on the dialog state.
     */
    fun saveSettings() {
        when (val dialogState = _dialogState.value) {
            is SettingsDialogState.AddNewSettings -> saveNewSettings(dialogState)
            is SettingsDialogState.EditSettings -> saveEditedSettings(dialogState)
            else -> return
        }
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
            val currentSettings = currentConfigData?.settings
            if (currentSettings == null) {
                println("Cannot delete settings: settings list is not available.")
                return@launch
            }

            settingsApi.deleteSettings(settingsId)
                .fold(
                    ifLeft = { error ->
                        println("Error deleting settings: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        updateSettingsInState { currentSettings ->
                            currentSettings.filter { it.id != settingsId }
                        }
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Cancels any dialog operation (adding new or editing existing settings).
     */
    fun cancelDialog() {
        _dialogState.value = SettingsDialogState.None
    }

    // --- Private Helper Functions ---

    /**
     * Helper function to update the settings part of the unified state while preserving models.
     * Only updates if the current state is Success, otherwise returns the state unchanged.
     */
    private fun updateSettingsInState(updateSettings: (List<ModelSettings>) -> List<ModelSettings>) {
        _settingsConfigState.update { state ->
            when (state) {
                is UiState.Success -> {
                    val updatedSettings = updateSettings(state.data.settings)
                    UiState.Success(state.data.copy(settings = updatedSettings))
                }

                else -> state
            }
        }
    }

    private fun saveNewSettings(dialogState: SettingsDialogState.AddNewSettings) {
        val form = dialogState.formState
        val validationError = form.validate()
        if (validationError != null) {
            updateSettingsFormError(validationError)
            return
        }
        val modelId = form.modelId ?: return
        val newSettings = form.toModelSettings(0L, modelId)
        viewModelScope.launch(uiDispatcher) {
            settingsApi.addModelSettings(newSettings)
                .fold(
                    ifLeft = { error ->
                        updateSettingsFormError("Error adding settings: ${error.message}")
                        println("Error adding settings: ${error.code} - ${error.message}")
                    },
                    ifRight = { createdSettings ->
                        updateSettingsInState { currentSettings ->
                            currentSettings + createdSettings
                        }
                        cancelDialog()
                    }
                )
        }
    }

    private fun saveEditedSettings(dialogState: SettingsDialogState.EditSettings) {
        val form = dialogState.formState
        val validationError = form.validate()
        if (validationError != null) {
            updateSettingsFormError(validationError)
            return
        }
        val modelId = form.modelId ?: return

        viewModelScope.launch(uiDispatcher) {
            val originalSettings = dialogState.settings
            val updatedSettings = form.toModelSettings(originalSettings.id, modelId)

            settingsApi.updateSettings(updatedSettings)
                .fold(
                    ifLeft = { error ->
                        updateSettingsFormError("Error updating settings: ${error.message}")
                        println("Error updating settings: ${error.code} - ${error.message}")
                    },
                    ifRight = {
                        updateSettingsInState { currentSettings ->
                            currentSettings.map {
                                if (it.id == updatedSettings.id) updatedSettings else it
                            }
                        }
                        cancelDialog()
                    }
                )
        }
    }

    private fun updateSettingsFormError(errorMessage: String?) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is SettingsDialogState.AddNewSettings -> dialogState.copy(
                    formState = dialogState.formState.withError(errorMessage)
                )

                is SettingsDialogState.EditSettings -> dialogState.copy(
                    formState = dialogState.formState.withError(errorMessage)
                )

                else -> dialogState
            }
        }
    }
}
