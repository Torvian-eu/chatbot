package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_unsupported_model_type
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SettingsRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.ModelSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
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
 * - Communicating with the backend via [SettingsRepository] and [ModelRepository].
 *
 * @constructor
 * @param settingsRepository The repository for Settings-related operations.
 * @param modelRepository The repository for Model-related operations (needed for model selection).
 * @param errorNotifier The service for handling and notifying about errors.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property modelsState The state of the list of all configured LLM models.
 * @property settingsListForSelectedModel The list of settings profiles for the selected model, or null if no model selected.
 * @property selectedModel The currently selected LLM model, or null if no model selected.
 * @property selectedSettings The currently selected settings profile in the master-detail UI, or null if no settings selected.
 * @property dialogState The current dialog state for the settings tab.
 */
class SettingsConfigViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelRepository: ModelRepository,
    private val errorNotifier: ErrorNotifier,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<SettingsConfigViewModel>()
    }

    // --- Private State Properties ---

    /**
     * The ID of the model the user has explicitly selected.
     */
    private val userSelectedModelId = MutableStateFlow<Long?>(null)

    /**
     * The ID of the settings profile the user has explicitly selected.
     */
    private val userSelectedSettingsId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<SettingsDialogState>(SettingsDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the list of all configured LLM models.
     */
    val modelsState: StateFlow<DataState<RepositoryError, List<LLMModel>>> = modelRepository.models

    /**
     * The state of the list of all configured settings profiles for the selected model.
     */
    val settingsListForSelectedModel: StateFlow<List<ModelSettings>?> = settingsRepository.settings
        .map { it.dataOrNull }
        .onEach { allSettingsList ->
            val supportedSettingsList = allSettingsList?.filter {
                isModelSettingsSupported(it)
            }
            if (supportedSettingsList?.size != allSettingsList?.size) {
                logger.warn("Found unsupported ModelSettings types. Only supported types are displayed.")
            }
        }
        .combine(userSelectedModelId) { allSettingsList, currentSelectedId ->
            allSettingsList?.filter {
                isModelSettingsSupported(it) && it.modelId == currentSelectedId
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The currently selected LLM model. Settings profiles will be loaded for this model.
     * If null, no model is selected and no settings are displayed.
     */
    val selectedModel: StateFlow<LLMModel?> = combine(
        modelsState.map { it.dataOrNull },
        userSelectedModelId
    ) { modelsList, currentSelectedId ->
        modelsList?.find { it.id == currentSelectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The currently selected settings profile in the master-detail UI.
     * If null, no settings profile is selected and no detail is displayed.
     */
    val selectedSettings: StateFlow<ModelSettings?> = combine(
        settingsListForSelectedModel,
        userSelectedSettingsId
    ) { settingsList, currentSelectedId ->
        settingsList?.find { it.id == currentSelectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The current dialog state for the settings tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<SettingsDialogState> = _dialogState.asStateFlow()

    // --- Initialization ---

    init {
        // Automatically select the first model on first load
        modelsState
            .filter { it is DataState.Success }
            .map { (it as DataState.Success).data.firstOrNull() }
            .onEach { firstModel ->
                selectModel(firstModel)
            }
            .launchIn(viewModelScope)
    }

    // --- Public Action Functions ---

    /**
     * Loads all configured LLM models and settings profiles.
     */
    fun loadModelsAndSettings() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { modelRepository.loadModels() },
                { settingsRepository.loadSettings() }
            ) { modelsResult, settingsResult ->
                modelsResult.mapLeft { error ->
                    errorNotifier.repositoryError(
                        error = error,
                        shortMessage = "Failed to load models"
                    )
                }
                settingsResult.mapLeft { error ->
                    errorNotifier.repositoryError(
                        error = error,
                        shortMessage = "Failed to load settings"
                    )
                }
            }
        }
    }

    /**
     * Selects an LLM model or clears selection when null.
     */
    fun selectModel(model: LLMModel?) {
        userSelectedModelId.value = model?.id
    }

    /**
     * Selects a settings profile or clears selection when null.
     */
    fun selectSettings(settings: ModelSettings?) {
        userSelectedSettingsId.value = settings?.id
    }

    /**
     * Initiates the process of adding a new settings profile by showing the form.
     */
    fun startAddingNewSettings() {
        val selectedModel = selectedModel.value ?: return
        if (selectedModel.type !in getSupportedSettingsTypes()) {
            viewModelScope.launch {
                errorNotifier.genericWarning(
                    shortMessageRes = Res.string.error_unsupported_model_type,
                    detailedMessage = "Cannot add new settings: Model type ${selectedModel.type} is not supported."
                )
            }
            return
        }
        _dialogState.value = SettingsDialogState.AddNewSettings(
            formState = createEmptyNewSettingsForm(selectedModel.type, selectedModel.id),
        )
    }

    /**
     * Initiates the editing process for an existing settings profile.
     */
    fun startEditingSettings(settings: ModelSettings) {
        if (!isModelSettingsSupported(settings)) {
            logger.warn("Cannot edit: Settings with ID ${settings.id} is of unsupported type ${settings::class.simpleName}.")
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
        viewModelScope.launch(uiDispatcher) {
            settingsRepository.deleteSettings(settingsId)
                .fold(
                    ifLeft = { error ->
                        errorNotifier.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete settings"
                        )
                    },
                    ifRight = {
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
            settingsRepository.addModelSettings(newSettings)
                .fold(
                    ifLeft = { error ->
                        errorNotifier.repositoryError(
                            error = error,
                            shortMessage = "Failed to add settings"
                        )
                        updateSettingsFormError("Error adding settings: ${error.message}")
                    },
                    ifRight = { createdSettings ->
                        cancelDialog()
                        selectSettings(createdSettings)
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

            settingsRepository.updateSettings(updatedSettings)
                .fold(
                    ifLeft = { error ->
                        errorNotifier.repositoryError(
                            error = error,
                            shortMessage = "Failed to update settings"
                        )
                        updateSettingsFormError("Error updating settings: ${error.message}")
                    },
                    ifRight = {
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
