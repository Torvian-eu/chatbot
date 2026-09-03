package eu.torvian.chatbot.server.service.core.chat.compaction

/**
 * Typed logical failures of the automated conversation-compaction policy.
 *
 * These errors terminate the active turn before any oversized primary request is sent. Categories
 * that describe an unusable configuration map to the model-configuration API error; categories that
 * describe a failed or insufficient compaction map to `CONVERSATION_COMPACTION_FAILED`.
 */
sealed interface ConversationCompactionError {

    /**
     * The configured compaction preference is structurally invalid or no longer resolvable.
     *
     * Covers malformed/partial JSON, blank instruction, non-positive IDs/threshold, missing or
     * inactive model/settings/provider, wrong settings-model pairing, streaming-only settings,
     * revoked access, or an unusable provider credential.
     *
     * @property reason Human-readable description of the invalid configuration.
     */
    data class InvalidConfiguration(val reason: String) : ConversationCompactionError

    /**
     * No provider strategy can serve the configured model/settings dialect.
     *
     * @property reason Human-readable description of the unsupported configuration.
     */
    data class UnsupportedConfiguration(val reason: String) : ConversationCompactionError

    /**
     * The auxiliary compaction LLM call failed at the provider boundary.
     *
     * @property reason Sanitized description of the generation failure (never raw provider bodies).
     */
    data class GenerationFailed(val reason: String) : ConversationCompactionError

    /**
     * The auxiliary compaction LLM call exceeded the bounded total timeout.
     */
    data object TimedOut : ConversationCompactionError

    /**
     * The auxiliary response was empty, had no choice, or requested tool calls.
     *
     * @property reason Human-readable description of the invalid output.
     */
    data class InvalidOutput(val reason: String) : ConversationCompactionError

    /**
     * The one-summary primary input still exceeds the configured threshold.
     *
     * @property sourceTokenCount Estimated full input before compaction.
     * @property resultTokenCount Estimated one-summary input after compaction.
     * @property thresholdTokens The configured threshold that was not met.
     */
    data class InsufficientReduction(
        val sourceTokenCount: Long,
        val resultTokenCount: Long,
        val thresholdTokens: Long
    ) : ConversationCompactionError

    /**
     * The source thread changed (missing message, edited timestamp, broken/mixed-session chain, or
     * leaf mismatch) between summary generation and verified persistence.
     *
     * @property reason Human-readable description of the source race.
     */
    data class SourceChanged(val reason: String) : ConversationCompactionError

    /**
     * Persisting the chunk or its coverage failed; the whole write was rolled back.
     *
     * @property reason Human-readable description of the persistence failure.
     */
    data class PersistenceFailed(val reason: String) : ConversationCompactionError
}
