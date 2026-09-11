package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ModelPresetDialogState
import eu.torvian.chatbot.app.domain.contracts.ModelPresetFormState
import eu.torvian.chatbot.app.domain.contracts.createEmptyModelPresetForm
import eu.torvian.chatbot.app.domain.contracts.toEditFormState
import eu.torvian.chatbot.app.repository.ModelPresetRepository
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.ModelSettingsRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for the Model Presets settings category.
 *
 * The ViewModel owns the preset list, the selected preset (master-detail) and the dialog/form state,
 * mirroring the [ProjectsViewModel] shape. It pulls the accessible models and settings profiles from
 * their repositories to feed the form's model picker, the model-gated settings picker and the
 * detail page's name resolution.
 *
 * Cache consistency: every successful preset create/update/delete also refreshes the role stream
 * inside [ModelPresetRepository], because an agent role's model/settings are derived from its preset.
 * This ViewModel therefore only loads the catalogs it needs and never mutates the role cache itself.
 *
 * @property modelPresetRepository Repository for preset CRUD and the reactive preset list
 *            (name-ascending, U-24).
 * @property modelRepository Repository of LLM models (offered unfiltered: the preset layer imposes no
 *            model-type restriction).
 * @property modelSettingsRepository Repository of settings profiles (offered unfiltered per selected
 *            model).
 * @property notificationService Service for error/success notifications.
 * @property uiDispatcher Dispatcher used for UI coroutines. Defaults to Main.
 */
class ModelPresetsViewModel(
    private val modelPresetRepository: ModelPresetRepository,
    private val modelRepository: ModelRepository,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<ModelPresetsViewModel>()
    }

    private val userSelectedPresetId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<ModelPresetDialogState>(ModelPresetDialogState.None)

    /** Reactive stream of all model presets owned by the current user (name-ascending). */
    val presetsState: StateFlow<DataState<RepositoryError, List<ModelPresetDto>>> = modelPresetRepository.presets

    /** All accessible LLM models offered by the form's model picker (unfiltered). */
    val modelsState: StateFlow<DataState<RepositoryError, List<LLMModel>>> = modelRepository.models

    /** All accessible settings profiles, used by the model-gated picker and the detail page. */
    val settingsState: StateFlow<DataState<RepositoryError, List<ModelSettings>>> =
        modelSettingsRepository.allSettings

    /** The preset selected in the master-detail UI, or null when on the list page. */
    val selectedPreset: StateFlow<ModelPresetDto?> = combine(
        presetsState.map { it.dataOrNull },
        userSelectedPresetId
    ) { presets, selectedId ->
        presets?.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Model lookup map for resolving a preset's model reference into a label. */
    val modelsById: StateFlow<Map<Long, LLMModel>> =
        modelsState.map { it.dataOrNull?.associateBy { model -> model.id } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /** Settings-profile lookup map for resolving a preset's settings reference into a label. */
    val settingsById: StateFlow<Map<Long, ModelSettings>> =
        settingsState.map { it.dataOrNull?.associateBy { settings -> settings.id } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /** The current dialog state for the tab. */
    val dialogState: StateFlow<ModelPresetDialogState> = _dialogState.asStateFlow()

    /**
     * Loads the preset list and the model/settings catalogs in parallel.
     *
     * Both catalogs are needed by the form (model picker, model-gated settings picker) and the detail
     * page (name resolution), so they reload whenever the tab enters.
     */
    fun loadPresetsAndCatalogs() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { modelPresetRepository.loadPresets() },
                { modelRepository.loadModels() },
                { modelSettingsRepository.loadAllSettings() }
            ) { presetsResult, modelsResult, settingsResult ->
                presetsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load model presets"
                    )
                }
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
     * Selects a preset for the master-detail view, or clears selection when null.
     */
    fun selectPreset(preset: ModelPresetDto?) {
        userSelectedPresetId.value = preset?.id
    }

    /**
     * Opens the add-preset form dialog with a fresh draft.
     */
    fun startAddingNewPreset() {
        _dialogState.value = ModelPresetDialogState.AddPreset(
            formState = createEmptyModelPresetForm()
        )
    }

    /**
     * Opens the edit-preset form dialog pre-filled from [preset].
     */
    fun startEditingPreset(preset: ModelPresetDto) {
        _dialogState.value = ModelPresetDialogState.EditPreset(
            preset = preset,
            formState = preset.toEditFormState()
        )
    }

    /**
     * Opens the delete-preset confirmation dialog for [preset].
     */
    fun startDeletingPreset(preset: ModelPresetDto) {
        _dialogState.value = ModelPresetDialogState.DeletePreset(preset)
    }

    /**
     * Applies an update function to the active form draft (add or edit dialog).
     *
     * The model-gated settings picker lives entirely in the View's `onFormUpdate` callbacks, so this
     * method never has to "repair" the draft: whatever the form sends is what is saved.
     */
    fun updatePresetForm(update: (ModelPresetFormState) -> ModelPresetFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is ModelPresetDialogState.AddPreset -> dialogState.copy(formState = update(dialogState.formState))
                is ModelPresetDialogState.EditPreset -> dialogState.copy(formState = update(dialogState.formState))
                else -> dialogState
            }
        }
    }

    /**
     * Saves the active form draft: creates a new preset for the add dialog, or replaces the
     * configuration for the edit dialog.
     */
    fun savePreset() {
        when (val dialogState = _dialogState.value) {
            is ModelPresetDialogState.AddPreset -> saveNewPreset(dialogState.formState)
            is ModelPresetDialogState.EditPreset -> saveEditedPreset(dialogState)
            else -> return
        }
    }

    /**
     * Deletes a preset and closes the confirmation dialog.
     *
     * Roles bound to the preset are kept by the server but become non-sendable; the repository
     * refreshes the role stream so the settings UI stops showing the deleted configuration.
     */
    fun deletePreset(presetId: Long) {
        viewModelScope.launch(uiDispatcher) {
            modelPresetRepository.deletePreset(presetId)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete model preset"
                        )
                    },
                    ifRight = {
                        // If the deleted preset was open in the detail page, fall back to the list.
                        if (userSelectedPresetId.value == presetId) {
                            userSelectedPresetId.value = null
                        }
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Cancels any dialog (form or confirmation).
     */
    fun cancelDialog() {
        _dialogState.value = ModelPresetDialogState.None
    }

    private fun saveNewPreset(formState: ModelPresetFormState) {
        val validationError = formState.validate()
        if (validationError != null) {
            updatePresetForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            modelPresetRepository.createPreset(formState.toCreateRequest())
                .fold(
                    ifLeft = { error ->
                        logger.warn("Failed to create model preset: ${error.message}")
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to create model preset"
                        )
                        updatePresetForm { it.withError("Error creating model preset: ${error.message}") }
                    },
                    ifRight = { createdPreset ->
                        cancelDialog()
                        selectPreset(createdPreset)
                    }
                )
        }
    }

    private fun saveEditedPreset(dialogState: ModelPresetDialogState.EditPreset) {
        val formState = dialogState.formState
        val validationError = formState.validate()
        if (validationError != null) {
            updatePresetForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            modelPresetRepository.updatePreset(dialogState.preset.id, formState.toUpdateRequest())
                .fold(
                    ifLeft = { error ->
                        logger.warn("Failed to update model preset: ${error.message}")
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to update model preset"
                        )
                        updatePresetForm { it.withError("Error updating model preset: ${error.message}") }
                    },
                    ifRight = { updatedPreset ->
                        cancelDialog()
                        selectPreset(updatedPreset)
                    }
                )
        }
    }
}
