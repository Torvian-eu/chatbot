package eu.torvian.chatbot.server.service.core.chat.compaction

/**
 * Effective compaction settings of one turn.
 *
 * Assembled once by resolution from the stored `conversation_compaction` preference, with the preset's
 * threshold override already applied, so a later edit cannot affect an in-flight turn. It describes the
 * auxiliary summarization configuration only: whether the turn may compact automatically is a separate
 * fact carried by the resolution. It is only ever built for a usable configuration: a missing,
 * incomplete or invalid preference is reported as [ResolvedCompactionConfig.Unusable] instead.
 *
 * Turning these settings into a usable runtime configuration (model/settings/provider/credential) is
 * still deferred until compaction is actually required.
 *
 * @property modelId Auxiliary summarization model referenced by the stored preference. Always positive.
 * @property settingsId Auxiliary settings profile referenced by the stored preference. Always positive.
 * @property instruction Instruction appended to the auxiliary request and recorded as chunk provenance.
 * @property systemMessage Optional auxiliary system prompt, or null when the preference has none.
 * @property summaryLabel Label prefix of the synthetic summary message.
 * @property thresholdTokens Effective threshold: the preset override when set, otherwise the stored
 *            preference's threshold. Both sources are validated as positive during preparation.
 */
data class EffectiveCompactionSettings(
    val modelId: Long,
    val settingsId: Long,
    val instruction: String,
    val systemMessage: String?,
    val summaryLabel: String,
    val thresholdTokens: Long
)
