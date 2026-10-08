package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Verifies the rolling-window pre-primary-call compaction policy end to end with mocked collaborators.
 *
 * The effective configuration is supplied to `beginTurn` directly, as turn preparation resolves it;
 * its own resolution matrix is covered by [DefaultEffectiveCompactionConfigResolverTest]. Cases here
 * cover window initialization (eligible-chunk seeding, stale-chunk fallback, raw-fit originals), the
 * disabled configuration that always sends the original thread, the content-free identity ledger and
 * its chunk-coverage equality, one-shot compaction to a single summary, hybrid reuse without an
 * auxiliary call, `InsufficientReduction` (summary alone and empty window), rolling across preflights,
 * the verification invariant, tool-free auxiliary calls that retain reasoning and append the
 * instruction as the final user message, labels, and timeout/provider/output/persistence failures that
 * must prevent any primary request.
 */
class DefaultConversationCompactionServiceTest {

    private val t0 = Instant.fromEpochMilliseconds(1_000L)
    private val t1 = Instant.fromEpochMilliseconds(1_001L)
    private val t2 = Instant.fromEpochMilliseconds(1_002L)
    private val t3 = Instant.fromEpochMilliseconds(1_003L)

    private val model = LLMModel(id = 1L, name = "gpt-4o", providerId = 1L, active = true)
    private val provider = LLMProvider(
        id = 1L, apiKeyId = null, name = "OpenAI", description = "OpenAI",
        baseUrl = "https://api.openai.com/v1", type = LLMProviderType.OPENAI
    )
    private val settings = ChatModelSettings(id = 1L, modelId = 1L, name = "Default", stream = false)
    private val primaryConfig = LLMConfig(provider, model, settings, apiKey = null)

    private val effectiveSettings = EffectiveCompactionSettings(
        modelId = 1L,
        settingsId = 1L,
        instruction = "Summarize faithfully",
        systemMessage = null,
        summaryLabel = ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
        thresholdTokens = 1_000L
    )

    /**
     * The effective compaction configuration of a session whose preset keeps its defaults: compaction
     * is enabled with the settings' own threshold. Used by every case that is not about the resolved
     * threshold itself.
     */
    private val defaultResolvedCompaction = ResolvedCompactionConfig.Enabled(settings = effectiveSettings)

    private val chunkDao = mockk<ConversationCompactionChunkDao>()
    private val configurationResolver = mockk<ConversationCompactionConfigurationResolver>()
    private val tokenCounter = mockk<ChatInputTokenCounter>()
    private val llmApiClient = mockk<LLMApiClient>()

    /** All collaborators are mocked, so no DB is touched. */
    private fun service(auxiliaryTimeout: Duration = 180.seconds) = DefaultConversationCompactionService(
        chunkDao = chunkDao,
        configurationResolver = configurationResolver,
        tokenCounter = tokenCounter,
        llmApiClient = llmApiClient,
        auxiliaryTimeout = auxiliaryTimeout
    )

    /**
     * Builds a context with one user unit per message id using the given (id, updatedAt) pairs.
     */
    private fun contextOf(vararg snapshots: Pair<Long, Instant>): ConversationContext = ConversationContext(
        snapshots.map { (id, updatedAt) ->
            ConversationContextUnit(
                source = SourceMessageSnapshot(id, updatedAt),
                rawMessages = listOf(RawChatMessage.User("m$id"))
            )
        }
    )

    /**
     * A counter mock deriving a deterministic count from the messages: every non-summary message
     * counts [unitTokens] and every labeled summary message counts [summaryTokens], mirroring the
     * authoritative counter's whole-input semantics.
     */
    private fun stubCounter(
        unitTokens: Long = 5_000L,
        summaryTokens: Long = 50L,
        summaryLabel: String = effectiveSettings.summaryLabel
    ) {
        coEvery { tokenCounter.countPrimaryInput(any(), any(), any(), any(), any(), any()) } answers {
            val messages = arg<List<RawChatMessage>>(4)
            val tokens = messages.sumOf { message ->
                if (message.content?.startsWith(summaryLabel) == true) summaryTokens else unitTokens
            }
            tokens.right()
        }
        // The persisted chunk records the counter version for provenance.
        every { tokenCounter.version } returns "test-fixed-v1"
    }

    /**
     * Stubs the retained-chunk read of an enabled turn to an empty list; disabled turns never read
     * chunks, so a stub set here goes unused for them.
     */
    private fun stubRetainedChunks() {
        coEvery { chunkDao.getChunksBySessionId(any()) } returns emptyList()
    }

    /**
     * Stubs a successful auxiliary resolution for the given effective settings.
     *
     * @param completion Completion the auxiliary client returns.
     * @param compactionSettings Settings the service must resolve; defaults to the turn's effective
     *            settings.
     */
    private fun stubAuxiliarySuccess(
        completion: LLMCompletionResult = completionWith("A concise summary."),
        compactionSettings: EffectiveCompactionSettings = effectiveSettings
    ) {
        coEvery { configurationResolver.resolveAuxiliaryConfig(1L, compactionSettings) } returns primaryConfig.right()
        coEvery {
            llmApiClient.completeChat(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns completion.right()
    }

    @Test
    fun `disabled state sends the original flattened thread`() = runTest {
        val context = contextOf(1L to t0, 2L to t1)

        val state = service().beginTurn(1L, 7L, context.units, ResolvedCompactionConfig.Disabled)
        val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { tokenCounter.countPrimaryInput(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a disabled configuration returns the disabled state and never compacts`() = runTest {
        val context = contextOf(1L to t0, 2L to t1)

        val state = service().beginTurn(1L, 7L, context.units, ResolvedCompactionConfig.Disabled)
        assertIs<CompactionTurnState.Disabled>(state)
        // A disabled turn mirrors the absent-row path: retained chunks are never loaded because
        // nothing could seed a window, and no counting ever runs (threshold is irrelevant).
        coVerify(exactly = 0) { chunkDao.getChunksBySessionId(any()) }

        val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { tokenCounter.countPrimaryInput(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an auxiliary configuration that no longer resolves fails only when compaction is required`() =
        runTest {
            // The referenced model/settings rows were deleted after the turn started, so the stored ids
            // no longer resolve. The configuration stays enabled: no error while the thread fits, but
            // once the window exceeds the threshold the resolver reports an invalid configuration.
            val deletedRows = effectiveSettings.copy(modelId = 99L, settingsId = 98L)
            val deletedRowsConfig = ResolvedCompactionConfig.Enabled(settings = deletedRows)
            stubRetainedChunks()
            stubCounter() // 2-unit thread = 10,000 tokens > 1,000 threshold -> compaction required
            coEvery { configurationResolver.resolveAuxiliaryConfig(1L, deletedRows) } returns
                ConversationCompactionError.InvalidConfiguration("Compaction model 99 not found").left()

            val state = service().beginTurn(1L, 7L, contextOf(1L to t0, 2L to t1).units, deletedRowsConfig)
            assertIs<CompactionTurnState.Enabled>(state)

            val error = assertIs<ConversationCompactionError.InvalidConfiguration>(
                service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).leftOrNull()
            )
            assertEquals("Compaction model 99 not found", error.reason)
            // The resolver failure aborts before any auxiliary call or persistence.
            coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
        }

    @Test
    fun `an auxiliary configuration that no longer resolves passes through while the thread fits`() = runTest {
        val deletedRows = effectiveSettings.copy(modelId = 99L, settingsId = 98L)
        stubRetainedChunks()
        stubCounter(unitTokens = 100L) // 2-unit thread = 200 tokens <= 1,000 threshold -> fits

        val state = service().beginTurn(
            1L, 7L, contextOf(1L to t0, 2L to t1).units,
            ResolvedCompactionConfig.Enabled(settings = deletedRows)
        )
        assertIs<CompactionTurnState.Enabled>(state)
        val preflight =
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).getOrNull()
        assertNotNull(preflight)
        assertEquals(contextOf(1L to t0, 2L to t1).flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        // The thread never exceeded the threshold, so no compaction and no configuration error.
        coVerify(exactly = 0) { configurationResolver.resolveAuxiliaryConfig(any(), any()) }
    }

    @Test
    fun `full raw input that fits sends originals and ignores retained chunks`() = runTest {
        stubRetainedChunks()
        stubCounter(unitTokens = 100L) // below threshold
        val context = contextOf(1L to t0, 2L to t1)

        val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
        val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { configurationResolver.resolveAuxiliaryConfig(any(), any()) }
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `oversized window compacts one-shot, persists a ledger-backed chunk, and leaves the summary alone`() =
        runTest {
            stubRetainedChunks()
            stubCounter()
            stubAuxiliarySuccess()
            val candidates = mutableListOf<ConversationCompactionChunkCandidate>()
            coEvery { chunkDao.insertVerifiedChunk(capture(candidates), 2L) } returns persistedChunk(id = 55L).right()

            val context = contextOf(1L to t0, 2L to t1)
            val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
            val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
                .getOrNull()
            assertNotNull(preflight)

            // Window after compaction is the summary message alone.
            assertEquals(1, preflight.primaryMessages.size)
            val summary = preflight.primaryMessages.single() as RawChatMessage.User
            assertTrue(summary.content.startsWith(effectiveSettings.summaryLabel))
            assertEquals("A concise summary.", summary.content.removePrefix(effectiveSettings.summaryLabel))
            assertEquals(55L, preflight.persistedChunkIfAny?.id)

            // The service updated its own state: the window is the summary only, the ledger holds the
            // compacted identities content-free, and the summary chunk id is recorded.
            val enabled = state as CompactionTurnState.Enabled
            assertTrue(enabled.units.isEmpty())
            assertEquals(summary, enabled.summaryMessage)
            assertEquals(55L, enabled.summaryChunkId)
            assertEquals(listOf(1L, 2L), enabled.coveredSnapshots.map { it.id })
            assertEquals(listOf(t0, t1), enabled.coveredSnapshots.map { it.updatedAt })

            // The persisted candidate's coverage is built from the ledger: ordinals 0..n-1, root to leaf.
            val candidate = candidates.single()
            assertEquals(listOf(0, 1), candidate.coverage.map { it.ordinal })
            assertEquals(listOf(1L, 2L), candidate.coverage.map { it.messageId })
            assertEquals(listOf(t0, t1), candidate.coverage.map { it.observedUpdatedAt })
            assertEquals(10_000L, candidate.sourceTokenCount)
            assertEquals(50L, candidate.resultTokenCount)
            assertEquals(1_000L, candidate.thresholdTokens)

            // Exactly one auxiliary call: one-shot, no repeat loop.
            coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { chunkDao.insertVerifiedChunk(any(), 2L) }
        }

    @Test
    fun `the settings summary label prefixes the generated summary`() = runTest {
        val customSettings = effectiveSettings.copy(summaryLabel = "Custom summary:\n")
        stubRetainedChunks()
        stubCounter(summaryLabel = customSettings.summaryLabel)
        stubAuxiliarySuccess(compactionSettings = customSettings)
        coEvery { chunkDao.insertVerifiedChunk(any(), any()) } returns persistedChunk(id = 56L).right()

        val context = contextOf(1L to t0, 2L to t1)
        val state = service().beginTurn(
            1L, 7L, context.units,
            ResolvedCompactionConfig.Enabled(settings = customSettings)
        )
        val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
            .getOrNull()
        assertNotNull(preflight)

        // The summary message is labeled with the settings' own summaryLabel, not a hardcoded label.
        val summary = preflight.primaryMessages.single() as RawChatMessage.User
        assertTrue(summary.content.startsWith(customSettings.summaryLabel))
        assertEquals("A concise summary.", summary.content.removePrefix(customSettings.summaryLabel))
        assertNotNull(preflight.persistedChunkIfAny)
    }

    @Test
    fun `window init with an eligible chunk seeds the summary and ledger and trims units to the delta`() =
        runTest {
            stubRetainedChunks()
            // raw thread (3 units) exceeds the 1000 threshold; [summary] + delta fits.
            stubCounter(unitTokens = 500L, summaryTokens = 50L)
            val priorChunk = chunkOf(
                id = 40L,
                createdAt = 1_000L,
                coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
            )
            coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(priorChunk)

            val context = contextOf(1L to t0, 2L to t1, 3L to t2)
            val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
            val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L)
                .getOrNull()
            assertNotNull(preflight)

            val enabled = state as CompactionTurnState.Enabled
            // The window is the seeded summary plus the delta only; the full thread content was released.
            assertEquals(listOf(3L), enabled.units.map { it.source.id })
            assertEquals(40L, enabled.summaryChunkId)
            // The ledger is seeded from the eligible chunk's persisted coverage, content-free.
            assertEquals(listOf(1L, 2L), enabled.coveredSnapshots.map { it.id })
            assertEquals(listOf(t0, t1), enabled.coveredSnapshots.map { it.updatedAt })

            // Hybrid reuse: [summary] + additional messages sent as-is, no auxiliary call, no persistence.
            assertEquals(2, preflight.primaryMessages.size)
            assertTrue(
                (preflight.primaryMessages[0] as RawChatMessage.User).content.startsWith(effectiveSettings.summaryLabel)
            )
            assertEquals("m3", (preflight.primaryMessages[1] as RawChatMessage.User).content)
            assertNull(preflight.persistedChunkIfAny)
            coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { configurationResolver.resolveAuxiliaryConfig(any(), any()) }
            coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
        }

    @Test
    fun `stale chunk falls back to the full thread with an empty ledger`() = runTest {
        stubRetainedChunks()
        stubCounter(unitTokens = 500L, summaryTokens = 50L)
        // The chunk's recorded timestamp for message 2 (1001) no longer matches the edited message
        // (now 1002), so it is ineligible and the window stays the full thread with an empty ledger.
        val staleChunk = chunkOf(
            id = 40L,
            createdAt = 1_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(staleChunk)

        val capturedInput = mutableListOf<List<RawChatMessage>>()
        coEvery {
            llmApiClient.completeChat(
                capture(capturedInput),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns completionWith("Rolled up.").right()
        coEvery { configurationResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()
        val candidates = mutableListOf<ConversationCompactionChunkCandidate>()
        coEvery { chunkDao.insertVerifiedChunk(capture(candidates), any()) } returns persistedChunk(id = 60L).right()

        val context = contextOf(1L to t0, 2L to t2, 3L to t3)
        val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
        service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L)
            .getOrNull()

        // The auxiliary input is the entire over-threshold thread (no eligible summary), one-time
        // cost, with the instruction appended as the closing user message.
        assertEquals(listOf("m1", "m2", "m3", "Summarize faithfully"), capturedInput.single().map { it.content })
        // The persisted chunk covers the full thread from the (empty-seeded) ledger.
        assertEquals(listOf(1L, 2L, 3L), candidates.single().coverage.map { it.messageId })
        assertEquals(listOf(t0, t2, t3), candidates.single().coverage.map { it.observedUpdatedAt })
    }

    @Test
    fun `hybrid reuse after a compaction performs no auxiliary call no config resolution and no persistence`() =
        runTest {
            stubRetainedChunks()
            stubCounter(unitTokens = 500L, summaryTokens = 50L)
            stubAuxiliarySuccess()
            coEvery { chunkDao.insertVerifiedChunk(any(), 3L) } returns persistedChunk(id = 55L).right()

            val context = contextOf(1L to t0, 2L to t1, 3L to t2)
            val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
            val firstPreflight =
                service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L).getOrNull()
            assertNotNull(firstPreflight)
            assertEquals(1, firstPreflight.primaryMessages.size)

            // A new tool-loop unit enters the rolling window.
            state.appendUnit(
                source = SourceMessageSnapshot(4L, t3),
                rawMessages = listOf(RawChatMessage.User("m4"))
            )

            // [summary] + the appended unit fits: the window is sent as-is.
            val secondPreflight =
                service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 4L).getOrNull()
            assertNotNull(secondPreflight)
            assertEquals(2, secondPreflight.primaryMessages.size)
            assertTrue(
                (secondPreflight.primaryMessages[0] as RawChatMessage.User).content.startsWith(effectiveSettings.summaryLabel)
            )
            assertEquals("m4", (secondPreflight.primaryMessages[1] as RawChatMessage.User).content)
            assertNull(secondPreflight.persistedChunkIfAny)

            // One auxiliary call and one persistence total (first preflight only).
            coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { configurationResolver.resolveAuxiliaryConfig(1L, effectiveSettings) }
            coVerify(exactly = 1) { chunkDao.insertVerifiedChunk(any(), any()) }
        }

    @Test
    fun `rolling across preflights feeds the prior summary plus content since into the second compaction`() =
        runTest {
            stubRetainedChunks()
            // 3-unit raw thread exceeds the 1000 threshold; [summary] alone fits; [summary] + a new unit
            // exceeds it again so the second preflight compacts once more.
            stubCounter(unitTokens = 600L, summaryTokens = 900L)
            stubAuxiliarySuccess()
            val candidates = mutableListOf<ConversationCompactionChunkCandidate>()
            coEvery { chunkDao.insertVerifiedChunk(capture(candidates), any()) } returnsMany listOf(
                persistedChunk(id = 55L).right(),
                persistedChunk(id = 56L).right()
            )

            val capturedInput = mutableListOf<List<RawChatMessage>>()
            coEvery {
                llmApiClient.completeChat(capture(capturedInput), any(), any(), any(), any(), any(), any())
            } returns completionWith("Rolled up.").right()

            val state = service().beginTurn(1L, 7L, contextOf(1L to t0, 2L to t1, 3L to t2).units, defaultResolvedCompaction)
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L).getOrNull()
            state.appendUnit(SourceMessageSnapshot(4L, t3), listOf(RawChatMessage.User("m4")))
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 4L).getOrNull()

            // Second compaction input = [first summary] + all content since (the newest appended
            // unit) + the instruction as the closing user message.
            assertEquals(2, capturedInput.size)
            val secondInput = capturedInput[1]
            assertEquals(3, secondInput.size)
            assertTrue((secondInput[0] as RawChatMessage.User).content.startsWith(effectiveSettings.summaryLabel))
            assertEquals("m4", (secondInput[1] as RawChatMessage.User).content)
            assertEquals("Summarize faithfully", (secondInput[2] as RawChatMessage.User).content)

            // The second chunk supersedes: coverage extends the ledger to the new leaf.
            val secondCandidate = candidates[1]
            assertEquals(listOf(0, 1, 2, 3), secondCandidate.coverage.map { it.ordinal })
            assertEquals(listOf(1L, 2L, 3L, 4L), secondCandidate.coverage.map { it.messageId })
            assertEquals(listOf(t0, t1, t2, t3), secondCandidate.coverage.map { it.observedUpdatedAt })

            val enabled = state as CompactionTurnState.Enabled
            assertEquals(56L, enabled.summaryChunkId)
            assertEquals(listOf(1L, 2L, 3L, 4L), enabled.coveredSnapshots.map { it.id })
            assertTrue(enabled.units.isEmpty())
        }

    @Test
    fun `summary alone over threshold raises insufficient reduction with no chunk and no repeat loop`() =
        runTest {
            stubRetainedChunks()
            stubCounter(unitTokens = 5_000L, summaryTokens = 2_000L) // post-count stays above threshold
            stubAuxiliarySuccess()

            val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
            val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)

            val error = assertIs<ConversationCompactionError.InsufficientReduction>(result.leftOrNull())
            assertEquals(5_000L, error.sourceTokenCount)
            assertEquals(2_000L, error.resultTokenCount)
            assertEquals(1_000L, error.thresholdTokens)

            // Exactly one auxiliary call: no repeat-compaction loop.
            coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
            // The state is untouched: the failed compaction neither persisted nor mutated the window.
            val enabled = state as CompactionTurnState.Enabled
            assertNull(enabled.summaryMessage)
            assertEquals(1, enabled.units.size)
        }

    @Test
    fun `empty window over threshold raises insufficient reduction without calling the auxiliary model`() =
        runTest {
            stubRetainedChunks()
            // The chunk covers the entire window, so seeding leaves no units; the summary alone (900)
            // still exceeds the 800 threshold, so there is nothing left to compact.
            stubCounter(unitTokens = 500L, summaryTokens = 900L)
            val coveringChunk = chunkOf(
                id = 40L,
                createdAt = 1_000L,
                coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
            )
            coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(coveringChunk)

            // The resolved threshold is 800 while the summary alone counts 900.
            val state = service().beginTurn(
                1L, 7L, contextOf(1L to t0, 2L to t1).units,
                ResolvedCompactionConfig.Enabled(settings = effectiveSettings.copy(thresholdTokens = 800L))
            )
            val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)

            val error = assertIs<ConversationCompactionError.InsufficientReduction>(result.leftOrNull())
            assertEquals(900L, error.sourceTokenCount)
            assertEquals(900L, error.resultTokenCount)
            assertEquals(800L, error.thresholdTokens)
            // Nothing to compact: no auxiliary call at all.
            coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
        }

    @Test
    fun `auxiliary call is tool-free keeps reasoning and appends the instruction as the final user message`() =
        runTest {
            stubRetainedChunks()
            stubCounter()
            // The resolved auxiliary config has an empty system message; the instruction comes from
            // the settings, not from the config.
            coEvery { configurationResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()

            val capturedMessages = mutableListOf<List<RawChatMessage>>()
            val capturedTools = mutableListOf<List<eu.torvian.chatbot.common.models.tool.ToolDefinition>?>()
            val capturedSystem = mutableListOf<String?>()
            coEvery {
                llmApiClient.completeChat(
                    capture(capturedMessages),
                    any(),
                    any(),
                    any(),
                    any(),
                    captureNullable(capturedTools),
                    captureNullable(capturedSystem)
                )
            } returns completionWith("Summary.").right()
            coEvery { chunkDao.insertVerifiedChunk(any(), any()) } returns persistedChunk(id = 55L).right()

            val reasoning = buildJsonObject { put("type", "reasoning"); put("id", "rs_1") }
            val context = ConversationContext(
                listOf(
                    ConversationContextUnit(SourceMessageSnapshot(1L, t0), listOf(RawChatMessage.User("first"))),
                    ConversationContextUnit(
                        SourceMessageSnapshot(2L, t1),
                        listOf(
                            RawChatMessage.Assistant(
                                content = "tool step",
                                toolCalls = listOf(
                                    RawChatMessage.Assistant.ToolCall(id = "c1", name = "search", arguments = "{}")
                                ),
                                reasoningItems = listOf(reasoning),
                                reasoningModelId = 5L
                            ),
                            RawChatMessage.Tool(content = "{}", toolCallId = "c1", name = "search")
                        )
                    )
                )
            )
            val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).getOrNull()

            val auxiliaryMessages = capturedMessages.single()
            // No tools, no system message; the window ends on a tool message here, yet the instruction
            // is always appended as the closing user turn so the request never ends on a non-user message.
            assertNull(capturedTools.single())
            assertNull(capturedSystem.single())
            assertEquals("Summarize faithfully", (auxiliaryMessages.last() as RawChatMessage.User).content)
            // Reasoning is retained: it can inform the summary and came from the producing model.
            val assistant = auxiliaryMessages.filterIsInstance<RawChatMessage.Assistant>().single()
            assertEquals(listOf(reasoning), assistant.reasoningItems)
            assertEquals(5L, assistant.reasoningModelId)
            assertEquals(1, auxiliaryMessages.count { it is RawChatMessage.Tool })
        }

    @Test
    fun `blank output is rejected as invalid and no chunk is persisted`() = runTest {
        stubRetainedChunks()
        stubCounter()
        stubAuxiliarySuccess(completionWith("   "))

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)
        assertIs<ConversationCompactionError.InvalidOutput>(result.leftOrNull())
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `tool-calling auxiliary output is rejected`() = runTest {
        stubRetainedChunks()
        stubCounter()
        val toolCall =
            LLMCompletionResult.CompletionChoice.ToolCallRequest(name = "search", arguments = "{}", toolCallId = "c1")
        val completion = LLMCompletionResult(
            id = "r1",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = "summary",
                    finishReason = "tool_calls",
                    index = 0,
                    toolCalls = listOf(toolCall)
                )
            ),
            usage = UsageStats(1, 1, 2)
        )
        stubAuxiliarySuccess(completion)

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)
        assertIs<ConversationCompactionError.InvalidOutput>(result.leftOrNull())
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `provider failure becomes a generation failure and blocks the primary request`() = runTest {
        stubRetainedChunks()
        stubCounter()
        coEvery { configurationResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) } returns
                LLMCompletionError.ApiError(500, "upstream boom", null).left()

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)
        assertIs<ConversationCompactionError.GenerationFailed>(result.leftOrNull())
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `a provider-declared ending fails the summary instead of becoming a chunk`() = runTest {
        stubRetainedChunks()
        stubCounter()
        // The provider answered with text, but declared that the generation did not complete: that text is a
        // truncated answer and must never be persisted as the summary of the messages it would cover.
        val declaredEnding = LLMCompletionError.ProviderFailureError(
            providerCode = "max_output_tokens",
            message = "The provider ended the response without completing it."
        )
        stubAuxiliarySuccess(completionWith("A cut-off summary.").copy(providerFailure = declaredEnding))

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)
        assertIs<ConversationCompactionError.GenerationFailed>(result.leftOrNull())
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `auxiliary timeout becomes a timed-out failure`() = runTest {
        stubRetainedChunks()
        stubCounter()
        coEvery { configurationResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(5_000.milliseconds)
            completionWith("late").right()
        }

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service(auxiliaryTimeout = 20.milliseconds).preparePrimaryContext(
            state = state,
            primaryConfig = primaryConfig,
            expectedLeafMessageId = 1L
        )
        assertIs<ConversationCompactionError.TimedOut>(result.leftOrNull())
    }

    @Test
    fun `persistence failure blocks the primary request`() = runTest {
        stubRetainedChunks()
        stubCounter()
        stubAuxiliarySuccess()
        coEvery { chunkDao.insertVerifiedChunk(any(), any()) } returns
                ConversationCompactionChunkDaoError.PersistenceFailed("disk full", null).left()

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)
        assertIs<ConversationCompactionError.PersistenceFailed>(result.leftOrNull())
    }

    @Test
    fun `source race during verified insert surfaces as source changed`() = runTest {
        stubRetainedChunks()
        stubCounter()
        stubAuxiliarySuccess()
        coEvery { chunkDao.insertVerifiedChunk(any(), any()) } returns
                ConversationCompactionChunkDaoError.SourceVerificationFailed("leaf changed").left()

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0).units, defaultResolvedCompaction)
        val result = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 1L)
        assertIs<ConversationCompactionError.SourceChanged>(result.leftOrNull())
    }

    @Test
    fun `a resolved threshold above the thread keeps the originals`() = runTest {
        // The settings' own threshold is 1_000 while the thread counts 10_000; the resolved threshold of
        // 12_000 is what keeps the turn uncompacted.
        stubRetainedChunks()
        stubCounter(unitTokens = 5_000L, summaryTokens = 50L)
        val context = contextOf(1L to t0, 2L to t1)
        val service = service()
        val state = assertIs<CompactionTurnState.Enabled>(
            service.beginTurn(
                1L, 7L, context.units,
                ResolvedCompactionConfig.Enabled(settings = effectiveSettings.copy(thresholdTokens = 12_000L))
            )
        )

        val preflight = assertNotNull(
            service.preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).getOrNull()
        )

        assertEquals(12_000L, state.settings.thresholdTokens)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { configurationResolver.resolveAuxiliaryConfig(any(), any()) }
    }

    @Test
    fun `a resolved threshold below the thread compacts and is recorded on the chunk`() = runTest {
        // The settings' own threshold is raised above the thread so only the resolved threshold can
        // trigger compaction, and the chunk must record the threshold that was actually in effect.
        val raisedSettings = effectiveSettings.copy(thresholdTokens = 100_000L)
        stubRetainedChunks()
        stubAuxiliarySuccess(compactionSettings = raisedSettings.copy(thresholdTokens = 12_000L))
        stubCounter(unitTokens = 5_000L, summaryTokens = 50L)
        val candidate = slot<ConversationCompactionChunkCandidate>()
        coEvery { chunkDao.insertVerifiedChunk(capture(candidate), any()) } returns persistedChunk(5L).right()
        val context = contextOf(1L to t0, 2L to t1, 3L to t2)
        val service = service()
        val state = assertIs<CompactionTurnState.Enabled>(
            service.beginTurn(
                1L, 7L, context.units,
                ResolvedCompactionConfig.Enabled(settings = raisedSettings.copy(thresholdTokens = 12_000L))
            )
        )

        val preflight = service.preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L).getOrNull()

        assertNotNull(preflight)
        assertEquals(12_000L, state.settings.thresholdTokens)
        assertEquals(
            12_000L,
            candidate.captured.thresholdTokens,
            "the persisted chunk records the preset override"
        )
    }

    /**
     * Builds a canned completion result with the given content.
     */
    private fun completionWith(content: String): LLMCompletionResult = LLMCompletionResult(
        id = "c1",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = content,
                finishReason = "stop",
                index = 0
            )
        ),
        usage = UsageStats(1, 1, 2)
    )

    /**
     * Builds a canned persisted chunk for assertion purposes.
     */
    private fun persistedChunk(id: Long): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = 7L,
        summary = "A concise summary.",
        modelId = 1L,
        settingsId = 1L,
        providerId = 1L,
        modelName = "gpt-4o",
        settingsName = "Default",
        providerName = "OpenAI",
        instruction = "Summarize faithfully",
        thresholdTokens = 1_000L,
        sourceTokenCount = 5_000L,
        resultTokenCount = 50L,
        tokenCounterVersion = "approx_utf16_json_v1",
        coverageCount = 2,
        createdAt = 9_000L,
        coverage = listOf(
            CompactedMessageCoverage(0, 1L, t0),
            CompactedMessageCoverage(1, 2L, t1)
        )
    )

    /**
     * Builds a retained chunk for window-init tests.
     */
    private fun chunkOf(
        id: Long,
        createdAt: Long,
        coverage: List<CompactedMessageCoverage>
    ): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = 7L,
        summary = "prior summary $id",
        modelId = 1L,
        settingsId = 1L,
        providerId = 1L,
        modelName = "gpt-4o",
        settingsName = "Default",
        providerName = "OpenAI",
        instruction = "Summarize faithfully",
        thresholdTokens = 1_000L,
        sourceTokenCount = 4_000L,
        resultTokenCount = 40L,
        tokenCounterVersion = "approx_utf16_json_v1",
        coverageCount = coverage.size,
        createdAt = createdAt,
        coverage = coverage
    )
}
