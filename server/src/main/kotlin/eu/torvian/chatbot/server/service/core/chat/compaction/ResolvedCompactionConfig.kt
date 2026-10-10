package eu.torvian.chatbot.server.service.core.chat.compaction

/**
 * Outcome of resolving a session's effective conversation-compaction configuration.
 *
 * Produced once during turn preparation, inside the turn's transaction, and fixed for the whole turn,
 * so a preset or preference edit made afterwards only affects the next turn. It answers whether a
 * usable auxiliary configuration exists and carries the turn's effective automatic-compaction flag on
 * either variant, because preparation has to reject a session that would have compacted automatically.
 *
 * It is deliberately not an `Either`: an unusable configuration is a legitimate, non-failing state that
 * only disables compaction and still has to reach the manual path as a reason.
 */
sealed interface ResolvedCompactionConfig {

    /**
     * A complete auxiliary configuration exists that can run a compaction. Threshold-triggered
     * compaction runs only when [automaticCompactionEnabled] is true; user-requested compaction and the
     * injection of an existing eligible summary are available either way.
     *
     * @property settings The turn's effective settings, carrying the effective threshold.
     * @property automaticCompactionEnabled Whether automatic (threshold-triggered) compaction is
     *            enabled: the preset's flag AND the stored preference's flag, with a missing preference
     *            row counting as disabled. It applies only to automatic compaction; manual compaction
     *            and summary injection ignore it.
     */
    data class Usable(
        val settings: EffectiveCompactionSettings,
        val automaticCompactionEnabled: Boolean
    ) : ResolvedCompactionConfig

    /**
     * No usable auxiliary configuration exists: it is absent, incomplete, or invalid. The turn proceeds
     * with the raw thread, and a user-requested (manual) compaction fails with [reason].
     *
     * @property reason Human-readable explanation of the missing or invalid configuration.
     * @property automaticCompactionEnabled Whether automatic (threshold-triggered) compaction is
     *            enabled, computed exactly as it is for a usable turn. When true, a session runtime
     *            that would compact automatically must be rejected instead of running without a working
     *            configuration.
     */
    data class Unusable(
        val reason: String,
        val automaticCompactionEnabled: Boolean
    ) : ResolvedCompactionConfig
}
