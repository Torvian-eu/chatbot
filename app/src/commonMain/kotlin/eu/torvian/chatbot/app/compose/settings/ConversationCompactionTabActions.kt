package eu.torvian.chatbot.app.compose.settings

/**
 * Action callbacks for the Conversation Compaction settings tab.
 */
interface ConversationCompactionTabActions {

    /**
     * Requests loading of the stored preference, accessible models, and settings profiles.
     */
    fun onLoad()

    /**
     * Selects the compaction model (clears the settings selection).
     *
     * @param modelId Selected model id, or null to clear.
     */
    fun onSelectModel(modelId: Long?)

    /**
     * Selects the settings profile for the chosen model.
     *
     * @param settingsId Selected settings id, or null to clear.
     */
    fun onSelectSettings(settingsId: Long?)

    /**
     * Toggles automatic compaction in the draft (persisted only by [onSave]).
     *
     * @param enable True to enable, false to disable temporarily while keeping the stored
     *            configuration.
     */
    fun onToggleEnabled(enable: Boolean)

    /**
     * Updates the compaction-instruction draft.
     *
     * @param text The new instruction text.
     */
    fun onUpdateInstruction(text: String)

    /**
     * Updates the optional system-message draft (blank maps to no system prompt on save).
     *
     * @param text The new system-message text.
     */
    fun onUpdateSystemMessage(text: String)

    /**
     * Updates the summary-label draft (blank reverts to the stable default on save).
     *
     * @param text The new summary-label text.
     */
    fun onUpdateSummaryLabel(text: String)

    /**
     * Updates the token-threshold draft.
     *
     * @param text The new threshold text.
     */
    fun onUpdateThreshold(text: String)

    /**
     * Validates and persists the current draft (including its enabled toggle) as the GLOBAL
     * preference row.
     */
    fun onSave()

    /**
     * Deletes the GLOBAL preference row entirely, discarding the stored configuration (destructive).
     * Use [onToggleEnabled] with [onSave] to disable temporarily while preserving the configuration.
     */
    fun onClear()
}