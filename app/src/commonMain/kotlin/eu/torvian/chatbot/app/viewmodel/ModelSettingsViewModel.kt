package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.*
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_unsupported_model_type
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.ModelSettingsRepository
import eu.torvian.chatbot.app.repository.UserGroupRepository
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
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
 * - Managing settings access control (public/private, grant/revoke access).
 * - Communicating with the backend via [ModelSettingsRepository] and [ModelRepository].
 *
 * @constructor
 * @param modelSettingsRepository The repository for Settings-related operations.
 * @param modelRepository The repository for Model-related operations (needed for model selection).
 * @param userGroupRepository The repository for user group-related operations.
 * @param notificationService Service for notifications and error handling.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property modelsState The state of the list of all configured LLM models.
 * @property settingsListForSelectedModel The list of settings profiles for the selected model, or null if no model selected.
 * @property selectedModel The currently selected LLM model, or null if no model selected.
 * @property selectedSettings The currently selected settings profile in the master-detail UI, or null if no settings selected.
 * @property dialogState The current dialog state for the settings tab.
 */
class ModelSettingsViewModel(
    private val modelSettingsRepository: ModelSettingsRepository,
    private val modelRepository: ModelRepository,
    private val userGroupRepository: UserGroupRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<ModelSettingsViewModel>()
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
    private val _dialogState = MutableStateFlow<ModelSettingsDialogState>(ModelSettingsDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the list of all configured LLM models.
     */
    val modelsState: StateFlow<DataState<RepositoryError, List<LLMModel>>> = modelRepository.models

    /**
     * The state of the list of all configured settings profiles for the selected model.
     */
    val settingsListForSelectedModel: StateFlow<List<ModelSettingsDetails>?> = modelSettingsRepository.allSettingsDetails
        .map { it.dataOrNull }
        .onEach { allSettingsList ->
            val supportedSettingsList = allSettingsList?.filter {
                isModelSettingsSupported(it.settings)
            }
            if (supportedSettingsList?.size != allSettingsList?.size) {
                logger.warn("Found unsupported ModelSettings types. Only supported types are displayed.")
            }
        }
        .combine(userSelectedModelId) { allSettingsList, currentSelectedId ->
            allSettingsList?.filter {
                isModelSettingsSupported(it.settings) && it.settings.modelId == currentSelectedId
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
    val selectedSettings: StateFlow<ModelSettingsDetails?> = combine(
        settingsListForSelectedModel,
        userSelectedSettingsId
    ) { settingsList, currentSelectedId ->
        settingsList?.find { it.settings.id == currentSelectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * The current dialog state for the settings tab.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<ModelSettingsDialogState> = _dialogState.asStateFlow()

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
                { modelSettingsRepository.loadAllSettingsDetails() }
            ) { modelsResult, settingsResult ->
                modelsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load models"
                    )
                }
                settingsResult.mapLeft { error ->
                    notificationService.repositoryError(
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
    fun selectSettings(settingsDetails: ModelSettingsDetails?) {
        userSelectedSettingsId.value = settingsDetails?.settings?.id
    }

    /**
     * Initiates the process of adding a new settings profile by showing the form.
     */
    fun startAddingNewSettings() {
        val selectedModel = selectedModel.value ?: return
        if (selectedModel.type !in getSupportedSettingsTypes()) {
            viewModelScope.launch {
                notificationService.genericWarning(
                    shortMessageRes = Res.string.error_unsupported_model_type,
                    detailedMessage = "Cannot add new settings: Model type ${selectedModel.type} is not supported."
                )
            }
            return
        }
        _dialogState.value = ModelSettingsDialogState.AddNewSettings(
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
        _dialogState.value = ModelSettingsDialogState.EditSettings(
            settings = settings,
            formState = settings.toEditFormState()
        )
    }

    /**
     * Initiates the deletion process for a settings profile by showing the confirmation dialog.
     */
    fun startDeletingSettings(settings: ModelSettings) {
        _dialogState.value = ModelSettingsDialogState.DeleteSettings(settings)
    }

    /**
     * Updates any field in the current settings form.
     */
    fun updateSettingsForm(update: (ModelSettingsFormState) -> ModelSettingsFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is ModelSettingsDialogState.AddNewSettings -> dialogState.copy(
                    formState = update(dialogState.formState)
                )

                is ModelSettingsDialogState.EditSettings -> dialogState.copy(
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
            is ModelSettingsDialogState.AddNewSettings -> saveNewSettings(dialogState)
            is ModelSettingsDialogState.EditSettings -> saveEditedSettings(dialogState)
            else -> return
        }
    }

    /**
     * Deletes a specific LLM settings profile.
     */
    fun deleteSettings(settingsId: Long) {
        viewModelScope.launch(uiDispatcher) {
            modelSettingsRepository.deleteSettings(settingsId)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
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
        _dialogState.value = ModelSettingsDialogState.None
    }

    /**
     * Makes a settings profile publicly accessible.
     */
    fun makeSettingsPublic(settingsDetails: ModelSettingsDetails) {
        viewModelScope.launch(uiDispatcher) {
            modelSettingsRepository.makeSettingsPublic(settingsDetails.settings.id)
                .mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to make settings public"
                    )
                }
        }
    }

    /**
     * Makes a settings profile private.
     */
    fun makeSettingsPrivate(settingsDetails: ModelSettingsDetails) {
        viewModelScope.launch(uiDispatcher) {
            modelSettingsRepository.makeSettingsPrivate(settingsDetails.settings.id)
                .mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to make settings private"
                    )
                }
        }
    }

    /**
     * Opens the manage access dialog for a settings profile.
     */
    fun openManageAccessDialog(settingsDetails: ModelSettingsDetails) {
        viewModelScope.launch(uiDispatcher) {
            userGroupRepository.loadGroups().fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load user groups"
                    )
                },
                ifRight = {
                    // Get groups from repository state
                    val groups = userGroupRepository.groups.value.dataOrNull ?: emptyList()

                    // Define available access modes for settings
                    val availableAccessModes = listOf(AccessMode.READ.key, AccessMode.WRITE.key, AccessMode.MANAGE.key)

                    _dialogState.value = ModelSettingsDialogState.ManageAccess(
                        settingsDetails = settingsDetails,
                        availableGroups = groups,
                        grantAccessForm = GrantAccessFormState(
                            selectedAccessMode = availableAccessModes.first(),
                            availableAccessModes = availableAccessModes
                        )
                    )
                }
            )
        }
    }

    /**
     * Opens the grant access dialog within the manage access dialog.
     */
    fun openGrantAccessDialog() {
        _dialogState.update { state ->
            if (state is ModelSettingsDialogState.ManageAccess) {
                state.copy(showGrantDialog = true)
            } else state
        }
    }

    /**
     * Closes the grant access dialog.
     */
    fun closeGrantAccessDialog() {
        _dialogState.update { state ->
            if (state is ModelSettingsDialogState.ManageAccess) {
                state.copy(
                    showGrantDialog = false,
                    grantAccessForm = GrantAccessFormState() // Reset form
                )
            } else state
        }
    }

    /**
     * Updates the grant access form state.
     */
    fun updateGrantAccessForm(update: (GrantAccessFormState) -> GrantAccessFormState) {
        _dialogState.update { state ->
            if (state is ModelSettingsDialogState.ManageAccess) {
                state.copy(grantAccessForm = update(state.grantAccessForm))
            } else state
        }
    }

    /**
     * Grants access to a settings profile for a specific user group.
     */
    fun grantSettingsAccess(settingsId: Long, groupId: Long, accessMode: String) {
        viewModelScope.launch(uiDispatcher) {
            modelSettingsRepository.grantSettingsAccess(
                settingsId,
                groupId,
                accessMode
            ).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to grant access"
                    )
                },
                ifRight = { settingsDetails ->
                    // Refresh dialog state to reflect new access details
                    _dialogState.update { state ->
                        if (state is ModelSettingsDialogState.ManageAccess) {
                            state.copy(settingsDetails = settingsDetails)
                        } else state
                    }
                    closeGrantAccessDialog()
                }
            )
        }
    }

    /**
     * Revokes access to a settings profile from a specific user group.
     */
    fun revokeSettingsAccess(settingsId: Long, groupId: Long, accessMode: String) {
        viewModelScope.launch(uiDispatcher) {
            modelSettingsRepository.revokeSettingsAccess(
                settingsId,
                groupId,
                accessMode
            ).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to revoke access"
                    )
                },
                ifRight = { settingsDetails ->
                    // Refresh dialog state to reflect new access details
                    _dialogState.update { state ->
                        if (state is ModelSettingsDialogState.ManageAccess) {
                            state.copy(settingsDetails = settingsDetails)
                        } else state
                    }
                }
            )
        }
    }

    // --- Private Helper Functions ---

    private fun saveNewSettings(dialogState: ModelSettingsDialogState.AddNewSettings) {
        val form = dialogState.formState
        val validationError = form.validate()
        if (validationError != null) {
            updateSettingsFormError(validationError)
            return
        }
        val modelId = form.modelId ?: return
        val newSettings = form.toModelSettings(0L, modelId)
        viewModelScope.launch(uiDispatcher) {
            modelSettingsRepository.addModelSettings(newSettings)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
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

    private fun saveEditedSettings(dialogState: ModelSettingsDialogState.EditSettings) {
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

            modelSettingsRepository.updateSettings(updatedSettings)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
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
                is ModelSettingsDialogState.AddNewSettings -> dialogState.copy(
                    formState = dialogState.formState.withError(errorMessage)
                )

                is ModelSettingsDialogState.EditSettings -> dialogState.copy(
                    formState = dialogState.formState.withError(errorMessage)
                )

                else -> dialogState
            }
        }
    }
}
