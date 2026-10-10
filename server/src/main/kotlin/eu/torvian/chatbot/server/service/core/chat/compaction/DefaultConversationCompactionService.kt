package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Default [ConversationCompactionService] implementing the rolling-window pre-primary-call policy.
 *
 * The loop's only conversation state is a rolling context window — one optional labeled summary plus
 * the additional uncompressed messages — together with a content-free identity ledger of every
 * compacted message's `(id, updatedAt)`. The window is seeded from the largest eligible retained chunk
 * and every preflight verifies the exact window to be sent against the threshold with the
 * authoritative counter; when the window exceeds the threshold the service performs exactly one
 * compaction operation (the entire over-threshold window becomes the auxiliary input, the result is
 * one new summary, and the window becomes the summary alone), persists one immutable prefix chunk
 * whose coverage equals the ledger, and never repeats the compaction in a loop. Any failure aborts the
 * turn before an oversized primary request is sent.
 *
 * @property chunkDao Loads retained chunks and persists verified new chunks.
 * @property auxiliaryConfigResolver Resolves/validates the auxiliary configuration when required.
 * @property tokenCounter Repository-owned approximate input token counter.
 * @property summarizer Runs the bounded auxiliary non-streaming call and validates its output.
 */
class DefaultConversationCompactionService(
    private val chunkDao: ConversationCompactionChunkDao,
    private val auxiliaryConfigResolver: AuxiliaryCompactionConfigResolver,
    private val tokenCounter: ChatInputTokenCounter,
    private val summarizer: AuxiliaryCompactionSummarizer
) : ConversationCompactionService {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultConversationCompactionService::class.java)
    }

    /**
     * Result of a compaction attempt that did not fail.
     *
     * [NoReduction] only ever comes from the forced (user-requested) path.
     */
    private sealed interface CompactionAttempt {

        /**
         * One verified prefix chunk was persisted.
         *
         * @property chunk The persisted chunk, ready to be used as primary context.
         */
        data class Persisted(val chunk: ConversationCompactionChunk) : CompactionAttempt

        /**
         * Nothing was persisted because the summary was not smaller than the content it replaces.
         *
         * @property sourceTokenCount Count of the window the summary would have replaced.
         * @property resultTokenCount Count of the generated summary alone.
         */
        data class NoReduction(
            val sourceTokenCount: Long,
            val resultTokenCount: Long
        ) : CompactionAttempt
    }

    override suspend fun beginTurn(
        userId: Long,
        sessionId: Long,
        initialUnits: List<ConversationContextUnit>,
        resolvedCompaction: ResolvedCompactionConfig
    ): CompactionTurnState = when (resolvedCompaction) {
        is ResolvedCompactionConfig.Usable -> {
            // Retained chunks are loaded once per turn; later iterations extend the in-memory list as the
            // service persists new chunks instead of reloading the database on every tool step.
            val activeState = CompactionTurnState.Active(
                sessionId = sessionId,
                ownerUserId = userId,
                // Resolved once for the whole turn: a later preset edit cannot affect this turn, and
                // persisted chunks keep the threshold that was in effect.
                settings = resolvedCompaction.settings,
                automaticCompactionEnabled = resolvedCompaction.automaticCompactionEnabled,
                retainedChunks = chunkDao.getChunksBySessionId(sessionId).toMutableList(),
                units = initialUnits.toMutableList(),
                summaryMessage = null,
                coveredSnapshots = mutableListOf()
            )
            // The window and the ledger are seeded here, once, before the first preflight: seeding only
            // touches state fields, all of which exist by now, so it need not be deferred.
            initializeWindowFromEligibleChunk(activeState)
            activeState
        }

        // No usable auxiliary configuration: this branch loads no retained chunk and counts nothing,
        // so the original thread is always sent and no configuration error can be raised. A resolution
        // that has to reject the turn never reaches here, because preparation rejects it first.
        is ResolvedCompactionConfig.Unusable ->
            CompactionTurnState.Inactive(sessionId, initialUnits.toMutableList())
    }

    override suspend fun preparePrimaryContext(
        state: CompactionTurnState,
        primaryConfig: LLMConfig,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionError, PrimaryContextPreflight> = either {
        // No usable configuration: count nothing, inject nothing, regenerate nothing.
        if (state is CompactionTurnState.Inactive) {
            return@either PrimaryContextPreflight(
                primaryMessages = flattenUnits(state.units),
                persistedChunkIfAny = null
            )
        }
        // Every state reaching a preflight has a usable configuration: a turn whose configuration could
        // not be decoded or validated was rejected during preparation while automatic compaction was
        // enabled, and otherwise ran as `Inactive`, so this branch always carries resolved settings and
        // never fabricates a fallback threshold.
        val activeState = state as CompactionTurnState.Active
        val threshold = activeState.settings.thresholdTokens

        // The seeded window is the intended primary context, not an over-threshold fallback: it was
        // seeded from the largest eligible retained chunk when the turn started, even below the
        // threshold and even while automatic compaction is disabled.
        val windowMessages = windowMessages(activeState)

        // Automatic compaction disabled: the seeded window is sent as-is, with neither a count nor an
        // auxiliary call. The threshold is deliberately not consulted, because the user asked for no
        // automatic compaction.
        if (!activeState.automaticCompactionEnabled) {
            return@either PrimaryContextPreflight(
                primaryMessages = windowMessages,
                persistedChunkIfAny = null
            )
        }

        // --- Window check (every preflight, including the first). ---
        // windowMessages is the hybrid context: [summary] + additional uncompressed messages (or the
        // raw thread before any summary exists). If it fits, it is sent as-is — the common steady
        // state reuses the current summary with no auxiliary call, no config resolution, no persistence.
        val windowTokens = countPrimaryInput(primaryConfig, windowMessages).bind()
        if (windowTokens <= threshold) {
            return@either PrimaryContextPreflight(
                primaryMessages = windowMessages,
                persistedChunkIfAny = null
            )
        }

        // --- Compaction required; auxiliary configuration errors fail only now. ---
        val persistedChunk = when (
            val attempt = performCompaction(
                userId = activeState.ownerUserId,
                sessionId = activeState.sessionId,
                settings = activeState.settings,
                primaryConfig = primaryConfig,
                summary = activeState.summaryMessage?.content,
                units = activeState.units.toList(),
                sourceTokenCount = windowTokens,
                coveredSnapshots = activeState.coveredSnapshots.toList(),
                expectedLeafMessageId = expectedLeafMessageId
            ).bind()
        ) {
            is CompactionAttempt.Persisted -> attempt.chunk
            // Unreachable: a result that did not shrink below the threshold already failed above, and the
            // window it replaces is over that threshold. Kept so an impossible state cannot read as success.
            is CompactionAttempt.NoReduction -> raise(
                ConversationCompactionError.InsufficientReduction(
                    sourceTokenCount = attempt.sourceTokenCount,
                    resultTokenCount = attempt.resultTokenCount,
                    thresholdTokens = threshold
                )
            )
        }

        // Self-update: the state owns the window and the content-free ledger, so the persisted chunk is
        // recorded here and nothing outside this loop has to track it. The ledger extension and the
        // window replacement mirror exactly what the persisted chunk covers.
        val newSummary = RawChatMessage.User(activeState.settings.summaryLabel + persistedChunk.summary)
        activeState.coveredSnapshots = (
                activeState.coveredSnapshots + activeState.units.map { it.source }
                ).toMutableList()
        activeState.summaryMessage = newSummary
        activeState.units.clear()
        activeState.retainedChunks.add(persistedChunk)

        PrimaryContextPreflight(
            primaryMessages = listOf(newSummary),
            persistedChunkIfAny = persistedChunk
        )
    }

    override suspend fun compactNow(
        userId: Long,
        sessionId: Long,
        settings: EffectiveCompactionSettings,
        primaryConfig: LLMConfig,
        summary: String?,
        units: List<ConversationContextUnit>,
        coveredSnapshots: List<SourceMessageSnapshot>,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionError, ManualCompactionOutcome> = either {
        // The source count is the count of the exact window to be summarized, so the persisted
        // source/result pair keeps the same meaning as on the automatic path.
        val sourceTokenCount = countPrimaryInput(
            primaryConfig = primaryConfig,
            messages = buildCompactionInput(summary, units)
        ).bind()
        when (
            val attempt = performCompaction(
                userId = userId,
                sessionId = sessionId,
                settings = settings,
                primaryConfig = primaryConfig,
                summary = summary,
                units = units,
                sourceTokenCount = sourceTokenCount,
                coveredSnapshots = coveredSnapshots,
                expectedLeafMessageId = expectedLeafMessageId
            ).bind()
        ) {
            is CompactionAttempt.Persisted -> ManualCompactionOutcome.Persisted(attempt.chunk)
            is CompactionAttempt.NoReduction -> {
                logger.info(
                    "Skipped forced compaction for session {}: a {} token summary does not shrink " +
                            "the {} tokens it replaces",
                    sessionId,
                    attempt.resultTokenCount,
                    attempt.sourceTokenCount
                )
                ManualCompactionOutcome.Skipped(CompactionSkipReason.SUMMARY_NOT_SMALLER)
            }
        }
    }

    /**
     * Performs the one-shot compaction of a window and persists its verified chunk.
     *
     * Shared by the automatic preflight branch and [compactNow], so both paths produce interchangeable
     * chunks: one auxiliary call, the same sufficiency post-count, the same cumulative coverage, and one
     * atomic verified insert.
     *
     * The reduction rule is unconditional: a summary that is not smaller than the window it replaces is
     * reported as [CompactionAttempt.NoReduction] instead of being persisted. Only the forced path can
     * trigger it, because on the automatic path such a result fails the threshold check below first.
     *
     * @param userId Owner of the compaction preference supplying the auxiliary configuration.
     * @param sessionId Session owning the new chunk.
     * @param settings The effective compaction settings in effect for the request.
     * @param primaryConfig The primary configuration whose threshold and counter decide sufficiency.
     * @param summary The labeled summary text of an already-covered prefix (label included), or null
     *            when the window is the whole thread.
     * @param units The uncompressed units to summarize, in thread order.
     * @param sourceTokenCount Estimated primary input of the window before this compaction.
     * @param coveredSnapshots The cumulative identity ledger of already-compacted messages, in thread
     *            order; empty when nothing has been compacted yet.
     * @param expectedLeafMessageId Thread leaf the chunk must end at.
     * @return Either a [ConversationCompactionError] or the attempt outcome; nothing is persisted for
     *         a [CompactionAttempt.NoReduction] result.
     */
    private suspend fun performCompaction(
        userId: Long,
        sessionId: Long,
        settings: EffectiveCompactionSettings,
        primaryConfig: LLMConfig,
        summary: String?,
        units: List<ConversationContextUnit>,
        sourceTokenCount: Long,
        coveredSnapshots: List<SourceMessageSnapshot>,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionError, CompactionAttempt> = either {
        val threshold = settings.thresholdTokens

        // Empty-window edge case: when the window is already just the summary (or empty), there is no
        // uncompressed content left to compact — raising InsufficientReduction with the unreduced
        // window count avoids a pointless auxiliary call and a summary-of-summary.
        ensure(units.isNotEmpty()) {
            ConversationCompactionError.InsufficientReduction(
                sourceTokenCount = sourceTokenCount,
                resultTokenCount = sourceTokenCount,
                thresholdTokens = threshold
            )
        }

        val auxiliaryConfig = auxiliaryConfigResolver.resolveAuxiliaryConfig(
            userId = userId,
            settings = settings
        ).bind()
        logger.debug(
            "Running compaction for session {}: window {} tokens exceeds threshold {}",
            sessionId,
            sourceTokenCount,
            threshold
        )

        // One-shot compaction: the entire window becomes the input; the result is a single new summary
        // and the window becomes the summary alone. There is no loop.
        val input = buildCompactionInput(summary, units)
        val summaryText = summarizer.summarize(auxiliaryConfig, input, settings.instruction)
            // Reported here, at the call site, so the failure keeps this service's logger category,
            // level and message text.
            .onLeft { error ->
                if (error is ConversationCompactionError.TimedOut) {
                    logger.error(
                        "Compaction auxiliary call timed out after $AUXILIARY_COMPACTION_TIMEOUT_SECONDS seconds"
                    )
                }
            }
            .bind()
        val newSummary = RawChatMessage.User(settings.summaryLabel + summaryText)

        // Post-count the one-summary primary request with the primary dialect/tools; insufficient
        // reduction is a failure and must not persist a chunk.
        val resultTokens = countPrimaryInput(primaryConfig, listOf(newSummary)).bind()
        if (resultTokens > threshold) {
            raise(
                ConversationCompactionError.InsufficientReduction(
                    sourceTokenCount = sourceTokenCount,
                    resultTokenCount = resultTokens,
                    thresholdTokens = threshold
                )
            )
        }

        // Reduction rule: a summary that is not smaller than the window it replaces would only replace
        // faithful content with a lossy equivalent of the same or greater size. Both sides are primary-input
        // counts, so "smaller" means the summary message is cheaper than the `[summary] + units` window.
        if (resultTokens >= sourceTokenCount) {
            return@either CompactionAttempt.NoReduction(
                sourceTokenCount = sourceTokenCount,
                resultTokenCount = resultTokens
            )
        }

        // The chunk's coverage is the cumulative compacted prefix: the incoming ledger extended by the
        // source of every unit that was just summarized.
        val candidate = createChunkCandidate(
            sessionId = sessionId,
            summary = summaryText,
            auxiliaryConfig = auxiliaryConfig,
            instruction = settings.instruction,
            coveredSnapshots = coveredSnapshots + units.map { it.source },
            sourceTokenCount = sourceTokenCount,
            resultTokens = resultTokens,
            threshold = threshold
        )

        // The caller must not use the summary unless this atomic verified insert succeeds (root-to-leaf
        // against expectedLeafMessageId; unchanged DAO contract).
        val persistedChunk = chunkDao.insertVerifiedChunk(candidate, expectedLeafMessageId)
            .mapLeft { daoError ->
                when (daoError) {
                    is ConversationCompactionChunkDaoError.SourceVerificationFailed ->
                        ConversationCompactionError.SourceChanged(daoError.reason)

                    is ConversationCompactionChunkDaoError.PersistenceFailed ->
                        ConversationCompactionError.PersistenceFailed(daoError.reason)
                }
            }
            .bind()

        logger.info(
            "Persisted compaction chunk {} for session {}: source {} -> result {} tokens (threshold {})",
            persistedChunk.id,
            persistedChunk.sessionId,
            persistedChunk.sourceTokenCount,
            persistedChunk.resultTokenCount,
            persistedChunk.thresholdTokens
        )

        CompactionAttempt.Persisted(persistedChunk)
    }

    /**
     * Seeds the rolling window from the largest eligible retained chunk.
     *
     * Runs once when the turn starts, so the first preflight already sees the window it will send.
     * When an eligible prior chunk exists, the window is seeded with that chunk's labeled summary, the
     * identity ledger is seeded from the chunk's persisted coverage, and the covered prefix is dropped
     * from [CompactionTurnState.Active.units] (delta only; the full thread content is released). When no
     * eligible chunk exists the window stays the full thread and the ledger stays empty — the first
     * compaction is then a documented one-time full-thread cost.
     *
     * Runs for every active state, whether automatic compaction is enabled or disabled: an existing
     * eligible summary is the intended primary context in both cases.
     *
     * @param state The active turn state being initialized.
     */
    private fun initializeWindowFromEligibleChunk(state: CompactionTurnState.Active) {
        // Eligible chunks are nested cumulative prefixes, so the chunk covering the most source
        // message ids also supersedes every smaller eligible chunk — a single selection fully
        // seeds the window and the ledger.
        val largest = findLargestEligibleChunk(state.retainedChunks, ConversationContext(state.units))
            ?: return
        state.summaryMessage = RawChatMessage.User(state.settings.summaryLabel + largest.summary)
        state.coveredSnapshots = largest.coverage
            .map { covered -> SourceMessageSnapshot(id = covered.messageId, updatedAt = covered.observedUpdatedAt) }
            .toMutableList()
        state.units = state.units.drop(largest.coverageCount).toMutableList()
    }

    /**
     * Builds the rolling window messages for the current active state in primary order.
     *
     * @param state The active turn state (summary is null only before the first compaction or when no
     *            prior eligible chunk seeded the window).
     * @return `[summary] + flattened units`, or the flattened units when no summary exists.
     */
    private fun windowMessages(state: CompactionTurnState.Active): List<RawChatMessage> =
        listOfNotNull(state.summaryMessage) + flattenUnits(state.units)

    /**
     * Flattens window units into the ordered provider-facing raw message list.
     *
     * @param units Window units in thread order.
     * @return Raw messages in thread order.
     */
    private fun flattenUnits(units: List<ConversationContextUnit>): List<RawChatMessage> =
        units.flatMap { it.rawMessages }

    /**
     * Counts a candidate primary input with the authoritative counter.
     *
     * @param primaryConfig The primary configuration whose dialect/tools drive the projection.
     * @param messages The candidate message list (window or summary-only).
     * @return Either an unsupported-configuration error or the approximate token count.
     */
    private fun countPrimaryInput(
        primaryConfig: LLMConfig,
        messages: List<RawChatMessage>
    ): Either<ConversationCompactionError, Long> = tokenCounter.countPrimaryInput(
        model = primaryConfig.model,
        provider = primaryConfig.provider,
        settings = primaryConfig.settings,
        systemMessage = primaryConfig.systemMessage.takeIf { it.isNotBlank() },
        messages = messages,
        tools = primaryConfig.tools
    )

    /**
     * Builds the chunk candidate whose coverage is the identity ledger at persistence time.
     *
     * Coverage ordinals run 0..n-1 from the thread root over the cumulative compacted prefix; the
     * ledger always ends at the current thread leaf, so the unchanged DAO root-to-leaf verification
     * applies. No message content is recorded — only identities and observed timestamps.
     *
     * @param sessionId Owning session.
     * @param summary Validated summary text.
     * @param auxiliaryConfig The validated auxiliary configuration used for generation (provenance).
     * @param instruction The user's compaction instruction snapshot persisted as provenance.
     * @param coveredSnapshots The identity ledger (cumulative compacted prefix).
     * @param sourceTokenCount Estimated pre-compaction window input.
     * @param resultTokens Estimated summary-only window input after this compaction.
     * @param threshold Threshold in effect.
     * @return The candidate for verified atomic insertion.
     */
    private fun createChunkCandidate(
        sessionId: Long,
        summary: String,
        auxiliaryConfig: LLMConfig,
        instruction: String,
        coveredSnapshots: List<SourceMessageSnapshot>,
        sourceTokenCount: Long,
        resultTokens: Long,
        threshold: Long
    ): ConversationCompactionChunkCandidate {
        val coverage = coveredSnapshots.mapIndexed { ordinal, snapshot ->
            CompactedMessageCoverage(
                ordinal = ordinal,
                messageId = snapshot.id,
                observedUpdatedAt = snapshot.updatedAt
            )
        }
        return ConversationCompactionChunkCandidate(
            sessionId = sessionId,
            summary = summary,
            modelId = auxiliaryConfig.model.id,
            settingsId = auxiliaryConfig.settings.id,
            providerId = auxiliaryConfig.provider.id,
            modelName = auxiliaryConfig.model.name,
            settingsName = auxiliaryConfig.settings.name,
            providerName = auxiliaryConfig.provider.name,
            instruction = instruction,
            thresholdTokens = threshold,
            sourceTokenCount = sourceTokenCount,
            resultTokenCount = resultTokens,
            tokenCounterVersion = tokenCounter.version,
            createdAt = System.currentTimeMillis(),
            coverage = coverage
        )
    }
}
