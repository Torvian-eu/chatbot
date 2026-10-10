package eu.torvian.chatbot.common.models.api.me

import kotlinx.serialization.Serializable

/**
 * Serialized per-user global conversation-compaction configuration.
 *
 * This data class is stored as the string value of the well-known `conversation_compaction`
 * preference so that the later client settings UI can reuse the exact same serializer while the
 * server runs automated rolling-window compaction of oversized primary LLM contexts.
 *
 * The [systemMessage], [automaticCompactionEnabled], [thresholdTokens], and [summaryLabel] fields are
 * optional on the wire: when omitted from an otherwise valid JSON object, the deserializer applies
 * `systemMessage = null` (no system prompt), `automaticCompactionEnabled = true`,
 * [DEFAULT_COMPACTION_THRESHOLD_TOKENS], and [DEFAULT_COMPACTED_SUMMARY_LABEL] respectively.
 * [modelId] and [settingsId] are required keys but nullable: they are `null` when the referenced
 * model or settings row no longer exists (for example after a server-side deletion). While
 * [automaticCompactionEnabled] is `true` the server rejects a null or non-positive id, a blank
 * instruction, and a non-positive threshold.
 *
 * [automaticCompactionEnabled] is the user-level flag for **automatic** (threshold-triggered)
 * compaction only: it is ANDed with the preset's own automatic flag, so a `false` at either scope
 * disables automatic compaction while the stored configuration is preserved. Disabling never rejects a
 * turn and never removes the user's ability to request compaction manually from the chat top bar; a
 * manual request needs the stored configuration below to be complete and otherwise fails with a typed
 * server error. A null [modelId] or [settingsId] is legitimate while automatic compaction is disabled
 * (the request simply cannot be served), whereas an enabled preference must name positive ids, a
 * non-blank instruction and a positive threshold and is rejected at write time otherwise.
 *
 * **Automatic compaction disabled does not stop the forced-use rule:** an eligible, thread-applicable
 * summary is still injected into the primary context in every mode as long as the stored configuration
 * is usable.
 *
 * **Compaction-model context-window requirement:** the compaction model's context window should be at
 * least `threshold + headroom`, and at least the size of the largest single message, because the
 * rolling-window design sends the entire over-threshold window (≈ threshold plus the newest appended
 * unit(s) in the steady state) to the compaction model. This is a documented requirement; v1 performs
 * no hard check and an undersized window surfaces as a provider error.
 *
 * **Threshold-sufficiency contract:** the threshold must fit the produced summary plus some
 * additional uncompressed messages. If the threshold is smaller than the size of the summary alone,
 * the turn fails with `InsufficientReduction` after compaction — there is no repeated-compaction loop.
 *
 * **Summary self-containment expectation:** after a compaction the primary model sees only the labeled
 * summary message, so the [instruction] should make the compaction model produce a self-contained
 * summary that explains how the assistant should continue.
 *
 * @property modelId ID of the `LLMModel` used for the auxiliary summarization request, or `null` when
 *            the previously referenced model no longer exists. An enabled preference must carry a
 *            positive id, and the server rejects a null one at write time; a disabled one may omit it
 *            and simply cannot serve a manual request.
 * @property settingsId ID of the `ModelSettings` profile paired with [modelId], or `null` when the
 *            previously referenced settings no longer exist; only a non-streaming chat-like profile is
 *            valid for compaction. An enabled preference must carry a positive id, and the server
 *            rejects a null one at write time.
 * @property instruction The compaction/summarization instruction sent to the auxiliary model.
 * @property systemMessage Optional system prompt for the auxiliary compaction call, or `null` when
 *            the compaction model runs without one; it is passed through to the auxiliary `LLMConfig`.
 * @property thresholdTokens Approximate-token input threshold above which automatic compaction is
 *            triggered.
 * @property summaryLabel Label prefix of the synthetic user message that represents the compacted
 *            prefix in the primary context; defaults to the stable v1 label.
 * @property automaticCompactionEnabled Whether threshold-triggered compaction is enabled for the user;
 *            defaults to `true` so preference rows written before this field existed stay enabled. It is
 *            ANDed with the preset's own flag, so a `false` at either scope disables automatic
 *            compaction. Disabling preserves the stored configuration and never blocks a manual
 *            request, which only needs a usable configuration.
 */
@Serializable
data class ConversationCompactionPreference(
    val modelId: Long?,
    val settingsId: Long?,
    val instruction: String,
    val systemMessage: String? = null,
    val thresholdTokens: Long = DEFAULT_COMPACTION_THRESHOLD_TOKENS,
    val summaryLabel: String = DEFAULT_COMPACTED_SUMMARY_LABEL,
    val automaticCompactionEnabled: Boolean = true
) {
    companion object {
        /**
         * Default compaction threshold in approximate tokens used when the preference omits
         * `thresholdTokens`. This default does **not** enable compaction by itself: a user without any
         * `conversation_compaction` preference row keeps both automatic and manual compaction
         * unavailable, and an enabled row is still constrained by the preset's own flag.
         */
        const val DEFAULT_COMPACTION_THRESHOLD_TOKENS: Long = 100_000L

        /**
         * Default value of [ConversationCompactionPreference.summaryLabel]: the stable v1 label
         * prefix applied to the synthetic summary user message in the primary context.
         */
        const val DEFAULT_COMPACTED_SUMMARY_LABEL: String = "Compacted conversation summary:\n"
    }
}
