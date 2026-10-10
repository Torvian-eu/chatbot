package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.ModelSettingsRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * ViewModel for the "Conversation Compaction" settings category.
 *
 * Lets the user pick an accessible compaction model, a compatible non-streaming chat-like settings
 * profile for that model, edit the compaction instruction, an optional system message, the summary
 * label, and the token threshold (defaulting to 100,000), and save the configuration as one GLOBAL
 * `conversation_compaction` preference row or delete it (destructive DELETE). The switch is the
 * user-level flag for **automatic** (threshold-triggered) compaction only: the server compacts
 * automatically only while this flag AND the session preset's own flag are on, so turning it off and
 * saving keeps the stored configuration intact while disabling automatic summarization, and a preset
 * that disables it is never overridden. Manual compaction from the chat top bar is always available
 * and only needs a usable configuration; it is deliberately separate from [clear], which removes the row
 * entirely. Validation of the client-editable fields mirrors the server's structural rules (positive
 * ids, non-blank instruction, threshold > 0) and is surfaced as inline messages before any network
 * write; the model/settings pair is required only while the automatic switch is on, matching the
 * server's write-time leniency.
 *
 * @property userPreferenceRepository Repository backing the global preference row.
 * @property modelRepository Repository listing accessible models.
 * @property modelSettingsRepository Repository listing settings profiles with access details.
 * @property notificationService Service used to surface load/save failures to the user.
 * @property uiDispatcher Dispatcher used for UI-bound coroutines. Defaults to [Dispatchers.Main].
 */
class ConversationCompactionViewModel(
    private val userPreferenceRepository: UserPreferenceRepository,
    private val modelRepository: ModelRepository,
    private val modelSettingsRepository: ModelSettingsRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    /**
     * Reactive stream of the stored global preference; `null` means no row exists yet. Whether automatic
     * compaction actually runs also depends on the row's own switch (see
     * [ConversationCompactionPreference.automaticCompactionEnabled]) and on the preset's flag.
     */
    val storedPreference: StateFlow<ConversationCompactionPreference?> =
        userPreferenceRepository.compactionPreference

    /**
     * Draft-level automatic-compaction switch: whether automatic compaction should be enabled after the
     * next [save].
     *
     * Unlike [storedPreference], this flow only reflects the in-form toggle and is persisted solely
     * by [save]; turning it off and saving keeps the stored configuration intact while disabling
     * automatic compaction at runtime, because the server stores the flag with the row.
     */
    private val _draftAutomaticEnabled = MutableStateFlow(false)
    val draftAutomaticEnabled: StateFlow<Boolean> = _draftAutomaticEnabled.asStateFlow()

    /**
     * Reactive stream of accessible models, filtered to active ones (inactive models cannot drive an
     * auxiliary compaction call in production).
     */
    val models: StateFlow<DataState<RepositoryError, List<LLMModel>>> = modelRepository.models

    /**
     * Reactive stream of settings profiles currently selected by the user: chat-like (CHAT or
     * RESPONSES) non-streaming profiles belonging to [selectedModelId].
     */
    private val _selectedModelId = MutableStateFlow<Long?>(null)
    val selectedModelId: StateFlow<Long?> = _selectedModelId.asStateFlow()

    /**
     * Compatible settings profiles for the selected model, derived from the repository's details
     * stream. Only chat-like non-streaming profiles can drive the auxiliary compaction call.
     */
    val compatibleSettings: StateFlow<List<ModelSettingsDetails>> = combine(
        modelSettingsRepository.allSettingsDetails,
        _selectedModelId
    ) { allSettings, selectedModelIdValue ->
        allSettings.dataOrNull.orEmpty().filter { details ->
            val settings = details.settings
            selectedModelIdValue != null && settings.modelId == selectedModelIdValue &&
                isNonStreamingChatLike(settings)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _selectedSettingsId = MutableStateFlow<Long?>(null)
    val selectedSettingsId: StateFlow<Long?> = _selectedSettingsId.asStateFlow()

    private val _instruction = MutableStateFlow("")
    val instruction: StateFlow<String> = _instruction.asStateFlow()

    /**
     * Optional system-message draft; blank means no system prompt (mapped to `null` on save).
     */
    private val _systemMessage = MutableStateFlow("")
    val systemMessage: StateFlow<String> = _systemMessage.asStateFlow()

    /**
     * Summary-label draft: label prefix of the synthetic summary user message. Initialized to the
     * stable default; blank input reverts to that default on save (never persisted as blank).
     */
    private val _summaryLabel = MutableStateFlow(
        ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL
    )
    val summaryLabel: StateFlow<String> = _summaryLabel.asStateFlow()

    /** Threshold editor text; parsed on save so the user sees a validation message for garbage. */
    private val _thresholdText = MutableStateFlow(
        ConversationCompactionPreference.DEFAULT_COMPACTION_THRESHOLD_TOKENS.toString()
    )
    val thresholdText: StateFlow<String> = _thresholdText.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _validationErrors = MutableStateFlow<List<String>>(emptyList())
    val validationErrors: StateFlow<List<String>> = _validationErrors.asStateFlow()

    /**
     * Loads the stored preference, accessible models, and settings details in parallel.
     *
     * Fills the draft fields from the stored preference on success so the user can edit the existing
     * configuration instead of starting from scratch.
     */
    fun load() {
        viewModelScope.launch(uiDispatcher) {
            val modelsResult = modelRepository.loadModels()
            val settingsResult = modelSettingsRepository.loadAllSettingsDetails()
            val preferenceResult = userPreferenceRepository.syncPreferences()

            modelsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load compaction models"
                )
            }
            settingsResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load compaction settings"
                )
            }
            preferenceResult.onLeft { error ->
                notificationService.repositoryError(
                    error = error,
                    shortMessage = "Failed to load compaction preference"
                )
            }
            // Only apply the stored value right after an explicit sync, never reactively: a later
            // background sync (e.g. another settings tab refreshing preferences) emits the same value
            // and must not clobber in-progress edits.
            applyStoredPreference(userPreferenceRepository.compactionPreference.value)
        }
    }

    /**
     * Applies the stored preference to the draft fields.
     *
     * @param preference The decoded stored preference, or `null` when no row exists.
     */
    fun applyStoredPreference(preference: ConversationCompactionPreference?) {
        if (preference == null) {
            // Absent row: reset the draft to the disabled defaults (threshold default 100,000).
            _selectedModelId.value = null
            _selectedSettingsId.value = null
            _instruction.value = ""
            _systemMessage.value = ""
            _summaryLabel.value =
                ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL
            _thresholdText.value =
                ConversationCompactionPreference.DEFAULT_COMPACTION_THRESHOLD_TOKENS.toString()
            _draftAutomaticEnabled.value = false
            return
        }
        // A stored preference always carries a whole configuration (the server stores one row), so
        // overwriting the whole draft keeps the form consistent. The switch initializes from the stored
        // flag: a disabled row shows an editable form that is still fully usable for manual compaction.
        _selectedModelId.value = preference.modelId
        _selectedSettingsId.value = preference.settingsId
        _instruction.value = preference.instruction
        _systemMessage.value = preference.systemMessage.orEmpty()
        _summaryLabel.value = preference.summaryLabel.ifBlank {
            ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL
        }
        _thresholdText.value = preference.thresholdTokens.toString()
        _draftAutomaticEnabled.value = preference.automaticCompactionEnabled
    }

    /**
     * Toggles the draft-level automatic-compaction switch.
     *
     * The toggle is draft state only; the server row is updated by [save]. This is the mechanism for
     * disabling automatic compaction **temporarily**: toggling off and saving writes the disabled flag
     * into the stored row so the configuration survives, unlike [clear], which deletes the row outright.
     *
     * @param enable True to enable automatic compaction, false to disable it temporarily.
     */
    fun setAutomaticEnabled(enable: Boolean) {
        _draftAutomaticEnabled.value = enable
    }

    /**
     * Selects the compaction model; the settings selector is reset because profiles are model-bound.
     *
     * @param modelId ID of the selected model, or `null` to clear the selection.
     */
    fun selectModel(modelId: Long?) {
        _selectedModelId.value = modelId
        _selectedSettingsId.value = null
    }

    /**
     * Selects the settings profile for the chosen model.
     *
     * @param settingsId ID of the selected profile, or `null` to clear the selection.
     */
    fun selectSettings(settingsId: Long?) {
        _selectedSettingsId.value = settingsId
    }

    /**
     * Updates the compaction instruction draft.
     *
     * @param text The new instruction text.
     */
    fun updateInstruction(text: String) {
        _instruction.value = text
    }

    /**
     * Updates the optional system-message draft.
     *
     * @param text The new system-message text; blank maps to `null` (no system prompt) on save.
     */
    fun updateSystemMessage(text: String) {
        _systemMessage.value = text
    }

    /**
     * Updates the summary-label draft.
     *
     * @param text The new summary-label text; blank reverts to the stable default on save.
     */
    fun updateSummaryLabel(text: String) {
        _summaryLabel.value = text
    }

    /**
     * Updates the token-threshold editor text.
     *
     * @param text The new threshold text (validated on save).
     */
    fun updateThresholdText(text: String) {
        _thresholdText.value = text
    }

    /**
     * Validates the current draft and persists it (including the [draftAutomaticEnabled] switch) as the
     * GLOBAL `conversation_compaction` row.
     *
     * Saving a draft whose switch is off writes the disabled flag, which keeps the configuration at rest
     * while automatic compaction never runs; manual compaction stays available and only needs the stored
     * configuration to be usable. On success the stored preference flow is refreshed by the repository,
     * keeping the UI in sync with the server's canonical encoding. No write is attempted while validation
     * errors exist: the errors are shown inline instead.
     */
    fun save() {
        val validationErrors = validateDraft()
        _validationErrors.value = validationErrors
        if (validationErrors.isNotEmpty()) return

        val threshold = _thresholdText.value.trim().toLongOrNull() ?: return

        viewModelScope.launch(uiDispatcher) {
            _saving.value = true
            try {
                userPreferenceRepository.setCompactionPreference(
                    ConversationCompactionPreference(
                        // Null references are accepted while automatic compaction is disabled; a manual
                        // request then fails server-side because the configuration is incomplete.
                        modelId = _selectedModelId.value,
                        settingsId = _selectedSettingsId.value,
                        instruction = _instruction.value.trim(),
                        // Blank system message maps to null (no system prompt); trimming keeps the
                        // stored value canonical.
                        systemMessage = _systemMessage.value.trim().ifBlank { null },
                        // No trim: the default label deliberately ends with a newline and custom
                        // labels may too; blank input reverts to the stable default instead.
                        summaryLabel = _summaryLabel.value.ifBlank {
                            ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL
                        },
                        thresholdTokens = threshold,
                        // Persist the draft switch: disabling must keep the stored configuration row so
                        // it stays editable and manual compaction stays possible.
                        automaticCompactionEnabled = _draftAutomaticEnabled.value
                    )
                ).onLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to save conversation compaction configuration"
                    )
                }.onRight {
                    _validationErrors.value = emptyList()
                }
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * Deletes the GLOBAL row entirely, discarding the stored configuration (destructive).
     *
     * This is the permanent removal action; a temporary disabling that preserves the configuration is
     * [setAutomaticEnabled]`(false)` followed by [save]. The server contract treats an absent row as an
     * unusable configuration, so neither automatic nor manual compaction can run afterwards.
     */
    fun clear() {
        viewModelScope.launch(uiDispatcher) {
            _saving.value = true
            try {
                userPreferenceRepository.clearCompactionPreference()
                    .onLeft { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to clear conversation compaction configuration"
                        )
                    }
                    .onRight {
                        _validationErrors.value = emptyList()
                        // Deleting the row makes every configuration unusable; reset the draft so the
                        // form shows the disabled defaults instead of a stale configuration.
                        applyStoredPreference(null)
                    }
            } finally {
                _saving.value = false
            }
        }
    }

    /**
     * Validates the client-editable fields against the server's structural rules.
     *
     * The model/settings pair is required only while the automatic switch is on, mirroring the server's
     * write-time leniency: a disabled row may omit the references, and a manual request then fails with a
     * typed error instead of the client blocking a save the server would accept.
     *
     * @return A list of human-readable validation messages; empty when the draft is valid.
     */
    private fun validateDraft(): List<String> {
        val errors = mutableListOf<String>()
        if (_draftAutomaticEnabled.value) {
            if (_selectedModelId.value == null) {
                errors += "Select an accessible compaction model."
            }
            if (_selectedSettingsId.value == null) {
                errors += "Select a non-streaming settings profile for the chosen model."
            }
        }
        if (_instruction.value.isBlank()) {
            errors += "The compaction instruction must not be blank."
        }
        val threshold = _thresholdText.value.trim().toLongOrNull()
        if (threshold == null || threshold <= 0L) {
            errors += "The token threshold must be a positive number."
        }
        return errors
    }

    /**
     * Whether a settings profile can drive the auxiliary non-streaming compaction call.
     *
     * @param settings The settings profile to check.
     * @return True for CHAT/RESPONSES profiles with `stream = false`; false otherwise.
     */
    private fun isNonStreamingChatLike(settings: ModelSettings): Boolean = when (settings) {
        is ChatModelSettings -> !settings.stream
        is ResponsesModelSettings -> !settings.stream
        else -> false
    }
}