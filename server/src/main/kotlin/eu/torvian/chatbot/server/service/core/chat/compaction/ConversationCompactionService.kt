package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot

/**
 * Runs the automated conversation-compaction policy before every primary LLM call, and the
 * on-demand forced compaction a user can request from the chat screen.
 *
 * In the automated role the service is invoked once at the top of each tool-loop iteration and owns
 * the rolling context window: the window is seeded from the largest eligible retained chunk and is
 * sent unchanged while it fits the threshold; when it exceeds the threshold the service performs
 * exactly one compaction — the entire over-threshold window becomes the auxiliary input and the
 * window becomes the new summary message alone, with the identity ledger extended and one verified
 * prefix chunk persisted. Any failure aborts the turn before an oversized primary request is sent.
 *
 * The same one-shot summarization and verified insert is exposed to the user-requested path as
 * [compactNow], so a manually created chunk is indistinguishable from an automatically created one.
 *
 * The effective configuration, including whether automatic (threshold-triggered) compaction is enabled,
 * is resolved by turn preparation inside the turn's transaction and arrives here as a
 * [ResolvedCompactionConfig]: the preset and the per-user preference can only disable automatic
 * compaction and override the threshold, while the auxiliary summarization settings stay
 * preference-owned. The forced-use rule and the manual path depend only on whether the auxiliary
 * configuration is usable, never on that flag.
 */
interface ConversationCompactionService {

    /**
     * Builds the per-turn compaction state for a user/session.
     *
     * Performs no IO besides the retained-chunk load of a usable configuration and cannot fail: the
     * configuration was already resolved during turn preparation.
     *
     * @param userId Owner of the global preference.
     * @param sessionId Session whose retained chunks are loaded.
     * @param initialUnits The identity-bearing source units built once at turn start; they initialize
     *            the rolling window, after which the full uncompressed content is released and not
     *            retained across the loop.
     * @param resolvedCompaction The turn's resolved compaction configuration. A
     *            [ResolvedCompactionConfig.Unusable] result yields [CompactionTurnState.Inactive]
     *            without loading retained chunks; a [ResolvedCompactionConfig.Usable] one yields
     *            [CompactionTurnState.Active] carrying its effective settings and the turn's
     *            automatic-compaction flag. A configuration whose auxiliary rows vanish after
     *            preparation is still [CompactionTurnState.Active] — the `InvalidConfiguration` surfaces
     *            from [preparePrimaryContext] only when an automatic compaction becomes necessary.
     * @return The turn state, snapshotted for the whole turn.
     */
    suspend fun beginTurn(
        userId: Long,
        sessionId: Long,
        initialUnits: List<ConversationContextUnit>,
        resolvedCompaction: ResolvedCompactionConfig
    ): CompactionTurnState

    /**
     * Runs the preflight policy for one primary LLM call.
     *
     * An active state whose automatic compaction is disabled only participates in the forced-use rule:
     * the window is seeded from an eligible retained chunk and sent as-is, with no counting and no
     * auxiliary call, so no automatic summary is ever created.
     *
     * @param state The turn state from [beginTurn]; the same instance is reused across iterations and
     *            mutated in place as the window and ledger evolve.
     * @param primaryConfig The primary model/settings/provider/tools/system-prompt configuration.
     * @param expectedLeafMessageId Current thread leaf used by the verified chunk insert.
     * @return Either a [ConversationCompactionError] or the verified primary context plus any newly
     *         persisted chunk.
     */
    suspend fun preparePrimaryContext(
        state: CompactionTurnState,
        primaryConfig: LLMConfig,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionError, PrimaryContextPreflight>

    /**
     * Runs one forced compaction of a caller-supplied window, outside any turn.
     *
     * The window is `[summary] + units`; the resulting summary alone becomes the persisted chunk's
     * content and the chunk's coverage is `coveredSnapshots + units`. The same post-count rule as the
     * automatic path applies: a summary that still exceeds the effective threshold fails with
     * [ConversationCompactionError.InsufficientReduction] and persists nothing.
     *
     * Unlike a turn preflight, a below-threshold thread is compacted anyway, and the shared reduction rule
     * decides the outcome: a summary that is not smaller than the window it replaces (the summary message
     * against the `[summary] + units` window, both primary-input counts) persists nothing and is reported as
     * [ManualCompactionOutcome.Skipped] with [CompactionSkipReason.SUMMARY_NOT_SMALLER].
     *
     * Nothing is written to the transcript; the persisted chunk is only used as context by later calls.
     *
     * @param userId Owner of the compaction preference supplying the auxiliary configuration.
     * @param sessionId Session owning the new chunk.
     * @param settings The effective compaction settings in effect for the request.
     * @param primaryConfig The primary configuration whose threshold and counter decide sufficiency.
     * @param summary The labeled summary text of an eligible covered prefix, or null when the window is
     *            the whole thread. The label is applied by the caller, as on the automatic path.
     * @param units The uncompressed units to summarize, in thread order.
     * @param coveredSnapshots The cumulative identity ledger of already-compacted messages, in thread
     *            order; empty when nothing has been compacted yet.
     * @param expectedLeafMessageId Thread leaf the chunk must end at; the verified insert rejects a
     *            source that changed since the caller built [units].
     * @return Either a [ConversationCompactionError] or the persisted/skipped outcome.
     */
    suspend fun compactNow(
        userId: Long,
        sessionId: Long,
        settings: EffectiveCompactionSettings,
        primaryConfig: LLMConfig,
        summary: String?,
        units: List<ConversationContextUnit>,
        coveredSnapshots: List<SourceMessageSnapshot>,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionError, ManualCompactionOutcome>
}
