package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default [ConversationCompactionService] implementing the rolling-window pre-primary-call policy.
 *
 * The loop's only conversation state is a rolling context window — one optional labeled summary plus
 * the additional uncompressed messages — together with a content-free identity ledger of every
 * compacted message's `(id, updatedAt)`. Every preflight verifies the exact window to be sent
 * against the threshold with the authoritative counter; when the window exceeds the threshold the
 * service performs exactly one compaction operation (the entire over-threshold window becomes the
 * auxiliary input, the result is one new summary, and the window becomes the summary alone), persists
 * one immutable prefix chunk whose coverage equals the ledger, and never repeats the compaction in a
 * loop. Any failure aborts the turn before an oversized primary request is sent.
 *
 * @property userPreferenceDao Reads the global preference row (device rows cannot enable compaction).
 * @property chunkDao Loads retained chunks and persists verified new chunks.
 * @property configurationResolver Resolves/validates the auxiliary configuration when required.
 * @property tokenCounter Repository-owned approximate input token counter.
 * @property llmApiClient Provider-neutral client for the auxiliary non-streaming call.
 * @property json Shared JSON codec used to decode the preference.
 * @property auxiliaryTimeout Total timeout around the retrying auxiliary non-streaming call, including
 *            `RetryLLMApiClient` backoff; defaults to the approved 180 seconds. Injectable so tests can
 *            exercise the timeout path without waiting the full bound.
 */
class DefaultConversationCompactionService(
    private val userPreferenceDao: UserPreferenceDao,
    private val chunkDao: ConversationCompactionChunkDao,
    private val configurationResolver: ConversationCompactionConfigurationResolver,
    private val tokenCounter: ChatInputTokenCounter,
    private val llmApiClient: LLMApiClient,
    private val json: Json,
    private val auxiliaryTimeout: Duration = AUXILIARY_COMPACTION_TIMEOUT_SECONDS.seconds
) : ConversationCompactionService {

    companion object {
        private val logger: Logger = LogManager.getLogger(DefaultConversationCompactionService::class.java)

        /**
         * Total timeout (in seconds) around the entire retrying auxiliary non-streaming call,
         * including `RetryLLMApiClient` backoff. An operational bound, not a product decision.
         */
        private const val AUXILIARY_COMPACTION_TIMEOUT_SECONDS: Long = 180L

        /**
         * Maximum characters of a provider error message kept for the public failure surface.
         */
        private const val MAX_ERROR_MESSAGE_CHARS: Int = 300
    }

    override suspend fun beginTurn(
        userId: Long,
        sessionId: Long,
        initialUnits: List<ConversationContextUnit>
    ): Either<ConversationCompactionError, CompactionTurnState> = either {
        val rawValue = userPreferenceDao.getGlobalPreference(userId, PreferenceKeys.CONVERSATION_COMPACTION)
            ?.prefValue
            ?: return@either CompactionTurnState.Disabled(sessionId, initialUnits.toMutableList())

        // A structurally invalid preference is a hard configuration error reported at turn start as the
        // Either left, carrying the serialization exception's own message for diagnostics: there is no
        // partially-usable state (no threshold hint, no retained-chunk load), and the turn aborts
        // before any counting or compaction can run.
        val decoded = try {
            json.decodeFromString<ConversationCompactionPreference>(rawValue)
        } catch (e: Exception) {
            raise(
                ConversationCompactionError.InvalidConfiguration(
                    "The conversation_compaction preference is structurally invalid: " +
                        (e.message ?: "malformed JSON")
                )
            )
        }

        // A preference stored with `enabled = false` behaves exactly like an absent row: automatic
        // compaction is off for this turn, the original thread is always sent, and only the initial
        // units are retained — no retained-chunk load and no counting, so no configuration error is
        // ever raised. A `null` model/settings reference is **not** treated as disabled here: the
        // preference stays enabled and surfaces `InvalidConfiguration` only when compaction becomes
        // necessary (see preparePrimaryContext).
        if (!decoded.enabled) {
            return@either CompactionTurnState.Disabled(sessionId, initialUnits.toMutableList())
        }

        // Retained chunks are loaded once per turn; later iterations extend the in-memory list as the
        // service persists new chunks instead of reloading the database on every tool step.
        val retainedChunks = chunkDao.getChunksBySessionId(sessionId).toMutableList()
        CompactionTurnState.Enabled(
            sessionId = sessionId,
            ownerUserId = userId,
            preference = decoded,
            retainedChunks = retainedChunks,
            units = initialUnits.toMutableList(),
            summaryMessage = null,
            coveredSnapshots = mutableListOf(),
            summaryChunkId = null,
            initialized = false
        )
    }

    override suspend fun preparePrimaryContext(
        state: CompactionTurnState,
        primaryConfig: LLMConfig,
        expectedLeafMessageId: Long
    ): Either<ConversationCompactionError, PrimaryContextPreflight> = either {
        // Disabled by absence: count nothing, inject nothing, regenerate nothing.
        if (state is CompactionTurnState.Disabled) {
            return@either PrimaryContextPreflight(
                primaryMessages = flattenUnits(state.units),
                persistedChunkIfAny = null
            )
        }
        // Every state reaching a preflight is enabled and decoded: an undecodable preference already
        // failed beginTurn as an InvalidConfiguration left, so no threshold-hint fallback exists here.
        val enabledState = state as CompactionTurnState.Enabled
        val threshold = enabledState.preference.thresholdTokens

        // --- First preflight only: "do not inject retained chunks when the full raw thread fits". ---
        // The full raw thread is counted once at turn start from the built context; after this point
        // the window is the only context and the full uncompressed content is released.
        if (!enabledState.initialized) {
            enabledState.initialized = true
            val fullRawTokens = countPrimaryInput(primaryConfig, flattenUnits(enabledState.units)).bind()
            if (fullRawTokens <= threshold) {
                return@either PrimaryContextPreflight(
                    primaryMessages = flattenUnits(enabledState.units),
                    persistedChunkIfAny = null
                )
            }
            initializeWindowFromEligibleChunk(enabledState)
        }

        // --- Window check (every preflight, including the first). ---
        // windowMessages is the hybrid context: [summary] + additional uncompressed messages (or the
        // raw thread before any summary exists). If it fits, it is sent as-is — the common steady
        // state reuses the current summary with no auxiliary call, no config resolution, no persistence.
        val windowMessages = windowMessages(enabledState)
        val windowTokens = countPrimaryInput(primaryConfig, windowMessages).bind()
        if (windowTokens <= threshold) {
            return@either PrimaryContextPreflight(
                primaryMessages = windowMessages,
                persistedChunkIfAny = null
            )
        }

        // --- Compaction required; auxiliary configuration errors fail only now. ---
        // Empty-window edge case: when the over-threshold window is already just the summary (or
        // empty), there is no uncompressed content left to compact — raising InsufficientReduction
        // with the unreduced window count avoids a pointless auxiliary call and a summary-of-summary.
        ensure(enabledState.units.isNotEmpty()) {
            ConversationCompactionError.InsufficientReduction(
                sourceTokenCount = windowTokens,
                resultTokenCount = windowTokens,
                thresholdTokens = threshold
            )
        }

        val auxiliaryConfig = configurationResolver.resolveAuxiliaryConfig(
            userId = enabledState.ownerUserId,
            preference = enabledState.preference
        ).bind()
        logger.debug(
            "Running compaction for session {}: window {} tokens exceeds threshold {}",
            enabledState.sessionId,
            windowTokens,
            threshold
        )

        // One-shot compaction (ND-3/ND-4): the entire over-threshold window becomes the input; the
        // result is a single new summary and the window becomes the summary alone. There is no loop.
        val input = buildCompactionInput(enabledState.summaryMessage, enabledState.units)
        val generationResult = generateSummary(auxiliaryConfig, input, enabledState.preference.instruction).bind()
        val summaryText = validateSummaryOutput(generationResult).bind()
        val newSummary = RawChatMessage.User(enabledState.preference.summaryLabel + summaryText)

        // Post-count the one-summary primary request with the primary dialect/tools; insufficient
        // reduction is a failure (ND-4/ND-6) and must not persist a chunk or send the primary request.
        val resultTokens = countPrimaryInput(primaryConfig, listOf(newSummary)).bind()
        if (resultTokens > threshold) {
            raise(
                ConversationCompactionError.InsufficientReduction(
                    sourceTokenCount = windowTokens,
                    resultTokenCount = resultTokens,
                    thresholdTokens = threshold
                )
            )
        }

        // Ledger extension (whole window) before the content is dropped: every compacted message's
        // identity is recorded content-free, so the chunk coverage below equals the ledger.
        enabledState.coveredSnapshots.addAll(enabledState.units.map { it.source })
        enabledState.summaryMessage = newSummary
        enabledState.units.clear()

        val candidate = createChunkCandidate(
            sessionId = enabledState.sessionId,
            summary = summaryText,
            auxiliaryConfig = auxiliaryConfig,
            instruction = enabledState.preference.instruction,
            coveredSnapshots = enabledState.coveredSnapshots,
            sourceTokenCount = windowTokens,
            resultTokens = resultTokens,
            threshold = threshold
        )

        // The primary call must not start unless this atomic verified insert succeeds (root-to-leaf
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

        // Self-update: the state owns the window/ledger, so the caller does not need to record the
        // persisted chunk (the old `record` hook is gone).
        enabledState.summaryChunkId = persistedChunk.id
        enabledState.retainedChunks.add(persistedChunk)

        logger.info(
            "Persisted compaction chunk {} for session {}: source {} -> result {} tokens (threshold {})",
            persistedChunk.id,
            persistedChunk.sessionId,
            persistedChunk.sourceTokenCount,
            persistedChunk.resultTokenCount,
            persistedChunk.thresholdTokens
        )

        PrimaryContextPreflight(
            primaryMessages = listOf(newSummary),
            persistedChunkIfAny = persistedChunk
        )
    }

    /**
     * Initializes the rolling window from the largest eligible retained chunk (first preflight only).
     *
     * When the full raw thread is over the threshold and an eligible prior chunk exists, the window is
     * seeded with that chunk's labeled summary, the identity ledger is seeded from the chunk's
     * persisted coverage, and the covered prefix is dropped from [CompactionTurnState.Enabled.units]
     * (delta only; the full thread content is released). When no eligible chunk exists the window
     * stays the full thread and the ledger stays empty — the first compaction is then a documented
     * one-time full-thread cost.
     *
     * @param state The enabled turn state being initialized.
     */
    private fun initializeWindowFromEligibleChunk(state: CompactionTurnState.Enabled) {
        // Eligible chunks are nested cumulative prefixes, so the chunk covering the most source
        // message ids also supersedes every smaller eligible chunk — a single selection fully
        // seeds the window and the ledger.
        val largest = findLargestEligibleChunk(state.retainedChunks, ConversationContext(state.units))
            ?: return
        state.summaryMessage = RawChatMessage.User(state.preference.summaryLabel + largest.summary)
        state.summaryChunkId = largest.id
        state.coveredSnapshots = largest.coverage
            .map { covered -> SourceMessageSnapshot(id = covered.messageId, updatedAt = covered.observedUpdatedAt) }
            .toMutableList()
        state.units = state.units.drop(largest.coverageCount).toMutableList()
    }

    /**
     * Builds the rolling window messages for the current enabled state in primary order.
     *
     * @param state The enabled turn state (summary is null only before the first compaction or when no
     *            prior eligible chunk seeded the window).
     * @return `[summary] + flattened units`, or the flattened units when no summary exists.
     */
    private fun windowMessages(state: CompactionTurnState.Enabled): List<RawChatMessage> =
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
     * Invokes the auxiliary compaction model under a bounded total timeout.
     *
     * The user's compaction instruction travels as the final user message of the request, not as the
     * system message: the instruction reads naturally as the closing user turn that asks the model to
     * summarize the conversation. The config's system message (the preference's optional system prompt)
     * is passed through normally, so the two roles never collide.
     *
     * @param config The validated auxiliary configuration (no tools; `systemMessage` carries the
     *            preference's optional system prompt, empty when none is set).
     * @param messages The bounded compaction input (the over-threshold window).
     * @param instruction The user's compaction instruction (guaranteed non-blank by the resolver),
     *            appended as the final user message.
     * @return Either a compaction error or the raw completion result.
     */
    private suspend fun generateSummary(
        config: LLMConfig,
        messages: List<RawChatMessage>,
        instruction: String
    ): Either<ConversationCompactionError, LLMCompletionResult> {
        // The instruction is appended unconditionally as the closing user message: it tells the model
        // what to produce and guarantees the request never ends on an assistant (or tool) message —
        // a trailing assistant turn would ask the model to answer itself, which some providers reject
        // with a 400. resolveAuxiliaryConfig already rejects a blank instruction, so the appended
        // message is never empty.
        val auxiliaryMessages = messages + RawChatMessage.User(instruction)
        return try {
            val result = withTimeout(auxiliaryTimeout) {
                llmApiClient.completeChat(
                    messages = auxiliaryMessages,
                    modelConfig = config.model,
                    provider = config.provider,
                    settings = config.settings,
                    apiKey = config.apiKey,
                    tools = null,
                    systemMessage = config.systemMessage.takeIf { it.isNotBlank() }
                )
            }
            // TimeoutCancellationException is caught below; any other CancellationException (external
            // socket cancellation) must propagate as a coroutine cancellation, not a compaction error.
            result.mapLeft { llmError ->
                ConversationCompactionError.GenerationFailed(llmError.sanitizedMessage())
            }
        } catch (_: TimeoutCancellationException) {
            logger.error("Compaction auxiliary call timed out after $AUXILIARY_COMPACTION_TIMEOUT_SECONDS seconds")
            ConversationCompactionError.TimedOut.left()
        }
    }

    /**
     * Extracts a bounded, provider-body-free description from an LLM completion error.
     *
     * @receiver The provider error to describe.
     * @return A short message suitable for the public error surface and logs (never raw bodies).
     */
    private fun LLMCompletionError.sanitizedMessage(): String =
        when (this) {
            is LLMCompletionError.NetworkError -> message
            is LLMCompletionError.ApiError ->
                message?.take(MAX_ERROR_MESSAGE_CHARS) ?: "HTTP $statusCode"

            is LLMCompletionError.InvalidResponseError -> message
            is LLMCompletionError.AuthenticationError -> message
            is LLMCompletionError.ConfigurationError -> message
            is LLMCompletionError.OtherError -> message
        }

    /**
     * Validates the auxiliary response and extracts the trimmed summary text.
     *
     * @param result The raw completion result.
     * @return Either [ConversationCompactionError.InvalidOutput] or the trimmed non-blank summary.
     */
    private fun validateSummaryOutput(
        result: LLMCompletionResult
    ): Either<ConversationCompactionError, String> {
        val choice = result.choices.firstOrNull()
            ?: return ConversationCompactionError.InvalidOutput(
                "Compaction model returned no completion choices"
            ).left()
        if (!choice.toolCalls.isNullOrEmpty()) {
            return ConversationCompactionError.InvalidOutput(
                "Compaction model requested tool calls; compaction is a non-tool-calling request"
            ).left()
        }
        val content = choice.content?.trim()
        if (content.isNullOrBlank()) {
            return ConversationCompactionError.InvalidOutput(
                "Compaction model returned blank or empty summary content"
            ).left()
        }
        return content.right()
    }

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
