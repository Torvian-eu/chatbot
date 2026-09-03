package eu.torvian.chatbot.common.models.api.me

import kotlinx.serialization.Serializable

/**
 * Serialized per-user global conversation-compaction configuration.
 *
 * This data class is stored as the string value of the well-known `conversation_compaction`
 * preference so that the later client settings UI can reuse the exact same serializer while the
 * server runs automated rolling-window compaction of oversized primary LLM contexts.
 *
 * The [systemMessage], [enabled], [thresholdTokens], and [summaryLabel] fields are optional on the
 * wire: when omitted from an otherwise valid JSON object, the deserializer applies `systemMessage = null`
 * (no system prompt), `enabled = true`, [DEFAULT_COMPACTION_THRESHOLD_TOKENS], and
 * [DEFAULT_COMPACTED_SUMMARY_LABEL] respectively.
 * [modelId] and [settingsId] are required keys but nullable: they are `null` when the referenced
 * model or settings row no longer exists (for example after a server-side deletion), so client code
 * can store the preference without fabricating a placeholder id. The server rejects non-null
 * non-positive ids and a blank instruction.
 *
 * Setting [enabled] to `false` disables automatic compaction for the user exactly like having no
 * `conversation_compaction` preference row — the original thread is always sent, no compaction runs,
 * and no configuration error is raised — while preserving the stored configuration for later
 * re-enabling. A preference whose [modelId] or [settingsId] is `null` stays stored (the referenced
 * rows no longer exist); at runtime no error is raised while the thread fits the threshold, but when
 * compaction becomes necessary the server reports an invalid-configuration error because no compactor
 * can be resolved.
 *
 * **Compaction-model context-window requirement:** the compaction model's context window should be at
 * least `threshold + headroom`, and at least the size of the largest single message, because the
 * rolling-window design sends the entire over-threshold window (≈ threshold plus the newest appended
 * unit(s) in the steady state) to the compaction model. This is a documented requirement; v1 performs
 * no hard check and an undersized window surfaces as a provider error.
 *
 * **Threshold-sufficiency contract (ND-6):** the threshold must fit the produced summary plus some
 * additional uncompressed messages. If the threshold is smaller than the size of the summary alone,
 * the turn fails with `InsufficientReduction` after compaction — there is no repeated-compaction loop.
 *
 * **Summary self-containment expectation:** after a compaction the primary model sees only the labeled
 * summary message, so the [instruction] should make the compaction model produce a self-contained
 * summary that explains how the assistant should continue.
 *
 * @property modelId ID of the `LLMModel` used for the auxiliary summarization request, or `null` when
 *            the previously referenced model no longer exists; a `null` id raises an
 *            invalid-configuration error at runtime only when compaction is required, while
 *            `enabled = false` disables compaction without any error.
 * @property settingsId ID of the `ModelSettings` profile paired with [modelId], or `null` when the
 *            previously referenced settings no longer exist; only a non-streaming chat-like profile
 *            is valid for compaction. A `null` id raises an invalid-configuration error at runtime
 *            only when compaction is required.
 * @property instruction The compaction/summarization instruction sent to the auxiliary model.
 * @property systemMessage Optional system prompt for the auxiliary compaction call, or `null` when
 *            the compaction model runs without one; it is passed through to the auxiliary `LLMConfig`.
 * @property thresholdTokens Approximate-token input threshold above which compaction is triggered.
 * @property summaryLabel Label prefix of the synthetic user message that represents the compacted
 *            prefix in the primary context; defaults to the stable v1 label.
 * @property enabled Whether automatic compaction is enabled for the user; defaults to `true` so
 *            preference rows written before this field keep working. `false` behaves like an absent
 *            row at runtime while the configuration is preserved.
 */
@Serializable
data class ConversationCompactionPreference(
    val modelId: Long?,
    val settingsId: Long?,
    val instruction: String,
    val systemMessage: String? = null,
    val thresholdTokens: Long = DEFAULT_COMPACTION_THRESHOLD_TOKENS,
    val summaryLabel: String = DEFAULT_COMPACTED_SUMMARY_LABEL,
    val enabled: Boolean = true
) {
    companion object {
        /**
         * Default compaction threshold in approximate tokens used when the preference omits
         * `thresholdTokens`. This default does **not** enable compaction by itself: a user without
         * any `conversation_compaction` preference row — or with a row whose [enabled] is `false` —
         * keeps automatic compaction disabled.
         */
        const val DEFAULT_COMPACTION_THRESHOLD_TOKENS: Long = 100_000L

        /**
         * Default value of [ConversationCompactionPreference.summaryLabel]: the stable v1 label
         * prefix applied to the synthetic summary user message in the primary context.
         */
        const val DEFAULT_COMPACTED_SUMMARY_LABEL: String = "Compacted conversation summary:\n"
    }
}
