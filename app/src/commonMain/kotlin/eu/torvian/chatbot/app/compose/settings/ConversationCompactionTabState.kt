package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.llm.LLMModel

/**
 * State contract for the Conversation Compaction settings tab.
 *
 * @property models Accessible active models (loading/error/inline state from the repository).
 * @property selectedModel The model currently selected in the form, or null.
 * @property compatibleSettings Non-streaming chat-like settings profiles for the selected model.
 * @property selectedSettings The settings profile currently selected in the form, or null.
 * @property storedPreference The stored global preference, or null when no row exists yet.
 * @property enabled Whether automatic compaction is enabled in the current draft (toggle value,
 *            persisted on save; false disables temporarily while preserving the stored configuration).
 * @property instruction The compaction-instruction draft text.
 * @property systemMessage The optional system-message draft text (empty means none).
 * @property summaryLabel The summary-label draft text (label prefix of the synthetic summary message;
 *            blank reverts to the stable default).
 * @property thresholdText The token-threshold draft text (parsed on save).
 * @property validationErrors Human-readable validation messages; empty when the draft is valid.
 * @property saving Whether a save/clear network operation is in flight.
 */
data class ConversationCompactionTabState(
    val models: DataState<RepositoryError, List<LLMModel>>,
    val selectedModel: LLMModel?,
    val compatibleSettings: List<ModelSettingsDetails>,
    val selectedSettings: ModelSettingsDetails?,
    val storedPreference: ConversationCompactionPreference?,
    val enabled: Boolean,
    val instruction: String,
    val systemMessage: String,
    val summaryLabel: String,
    val thresholdText: String,
    val validationErrors: List<String>,
    val saving: Boolean
)