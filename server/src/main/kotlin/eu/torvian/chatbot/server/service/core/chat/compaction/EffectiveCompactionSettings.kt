package eu.torvian.chatbot.server.service.core.chat.compaction

/**
 * Effective compaction settings of one enabled turn.
 *
 * Assembled once by the resolution step from the stored `conversation_compaction` preference, with the
 * preset's threshold override already applied. It carries exactly what compaction consumes and nothing
 * else: there is no `enabled` flag (the carrier variant conveys that compaction is on) and exactly one
 * threshold, the effective one.
 *
 * The references are never null: a stored preference without an auxiliary model or settings is rejected
 * during turn preparation, so this value can only be built for a resolvable configuration. Turning it
 * into a usable runtime configuration (model/settings/provider/credential) is still deferred until
 * compaction is actually required.
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
