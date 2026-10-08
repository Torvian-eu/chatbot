package eu.torvian.chatbot.server.service.core.chat.compaction

/**
 * Complete effective conversation-compaction configuration of one turn.
 *
 * The value is produced once by turn preparation, inside the turn's transaction, and is then fixed for
 * the whole turn: a preset or preference edit made afterwards only affects the next turn, and chunks
 * already persisted keep the threshold that was in effect when they were created.
 *
 * The value carries the effective settings; resolving them into a usable `LLMConfig` still happens
 * lazily, only when a compaction becomes necessary.
 */
sealed interface ResolvedCompactionConfig {

    /**
     * Compaction is off for the turn.
     *
     * Three cases collapse here: the session's preset disables compaction (the preference is then
     * never read, so a malformed one cannot fail the turn), no global preference row exists, or the
     * stored preference has `enabled = false`.
     */
    data object Disabled : ResolvedCompactionConfig

    /**
     * Compaction is on for the turn.
     *
     * @property settings The turn's effective compaction settings, carrying the single effective
     *            threshold.
     */
    data class Enabled(
        val settings: EffectiveCompactionSettings
    ) : ResolvedCompactionConfig
}
