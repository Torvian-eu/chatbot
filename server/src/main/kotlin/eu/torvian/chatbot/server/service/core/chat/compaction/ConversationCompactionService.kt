package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit

/**
 * Runs the automated conversation-compaction policy before every primary LLM call.
 *
 * The service is invoked once at the top of each tool-loop iteration and owns the rolling context
 * window: when the window fits the threshold it is sent unchanged (originals on the first preflight,
 * `[summary] + additional messages` afterwards); when it exceeds the threshold, the service performs
 * exactly one compaction — the entire over-threshold window becomes the auxiliary input and the
 * window becomes the new summary message alone, with the identity ledger extended and one verified
 * prefix chunk persisted. Any failure aborts the turn before an oversized primary request is sent.
 */
interface ConversationCompactionService {

    /**
     * Loads the per-turn compaction state for a user/session.
     *
     * @param userId Owner of the global preference.
     * @param sessionId Session whose retained chunks are loaded.
     * @param initialUnits The identity-bearing source units built once at turn start; they initialize
     *            the rolling window, after which the full uncompressed content is released and not
     *            retained across the loop.
     * @return Either [ConversationCompactionError.InvalidConfiguration] when the global preference row
     *         exists but is structurally invalid (reported immediately, never as a partially-usable
     *         state), or the right turn state: [CompactionTurnState.Disabled] when no preference
     *         exists or the preference has `enabled = false`; [CompactionTurnState.Enabled] when it
     *         decoded successfully and is enabled. An enabled preference with a `null` model/settings
     *         reference is still [CompactionTurnState.Enabled] — the `InvalidConfiguration` surfaces
     *         from [preparePrimaryContext] only when compaction becomes necessary.
     */
    suspend fun beginTurn(
        userId: Long,
        sessionId: Long,
        initialUnits: List<ConversationContextUnit>
    ): Either<ConversationCompactionError, CompactionTurnState>

    /**
     * Runs the preflight policy for one primary LLM call.
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
}
