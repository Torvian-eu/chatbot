package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.server.data.dao.ConversationCompactionChunkDao
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.context.ChatContextBuilder
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.core.chat.persistence.ConversationTurnPersistence
import eu.torvian.chatbot.server.service.core.chat.preparation.ConversationTurnPreparationService
import eu.torvian.chatbot.server.service.core.chat.preparation.PreparedConversationTurn
import eu.torvian.chatbot.server.service.core.error.message.ValidateNewMessageError
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the user-requested compaction orchestration with mocked collaborators.
 *
 * The cases cover the input composition (whole thread without an eligible chunk, summary plus tail with
 * one, largest eligible chunk wins), the two no-op cases, the resolved-configuration and preparation
 * failures, a broken thread, the sufficiency failure, a non-reducing summary reported as a skip, and
 * cancellation. The service owns no persistence of its own, so every case also asserts that nothing was
 * inserted directly.
 */
class DefaultConversationManualCompactionServiceTest {

    private val t0 = Instant.fromEpochMilliseconds(1_000L)
    private val t1 = Instant.fromEpochMilliseconds(1_001L)
    private val t2 = Instant.fromEpochMilliseconds(1_002L)

    private val primaryConfig = LLMConfig(
        provider = TestDefaults.llmProvider1,
        model = TestDefaults.llmModel1,
        settings = TestDefaults.modelSettings1,
        apiKey = null
    )

    private val effectiveSettings = EffectiveCompactionSettings(
        modelId = TestDefaults.llmModel1.id,
        settingsId = TestDefaults.modelSettings1.id,
        instruction = "Summarize faithfully",
        systemMessage = null,
        summaryLabel = ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
        thresholdTokens = 1_000L
    )

    private val enabledCompaction =
        ResolvedCompactionConfig.Usable(settings = effectiveSettings, automaticCompactionEnabled = true)

    private val preparationService = mockk<ConversationTurnPreparationService>()
    private val conversationTurnPersistence = mockk<ConversationTurnPersistence>()
    private val chatContextBuilder = mockk<ChatContextBuilder>()
    private val chunkDao = mockk<ConversationCompactionChunkDao>()
    private val compactionService = mockk<ConversationCompactionService>()

    /** All collaborators are mocked, so no DB is touched. */
    private fun service() = DefaultConversationManualCompactionService(
        preparationService = preparationService,
        conversationTurnPersistence = conversationTurnPersistence,
        chatContextBuilder = chatContextBuilder,
        chunkDao = chunkDao,
        compactionService = compactionService
    )

    /** Builds a session as the preparation service would return it. */
    private fun sessionOf(leafMessageId: Long?, messages: List<ChatMessage>): ChatSession = ChatSession(
        id = SESSION_ID,
        name = "Session",
        createdAt = t0,
        updatedAt = t0,
        groupId = null,
        agentRoleId = 1L,
        currentLeafMessageId = leafMessageId,
        messages = messages
    )

    /** Builds a context with one user unit per message id. */
    private fun contextOf(vararg snapshots: Pair<Long, Instant>): ConversationContext = ConversationContext(
        snapshots.map { (id, updatedAt) ->
            ConversationContextUnit(
                source = SourceMessageSnapshot(id, updatedAt),
                rawMessages = listOf(RawChatMessage.User("m$id"))
            )
        }
    )

    /**
     * Stubs the preparation and tool-call reads for the session under test.
     *
     * @param leafMessageId Leaf the prepared session reports.
     * @param messages Messages the prepared session carries.
     * @param resolvedCompaction Effective compaction configuration the preparation resolves.
     */
    private fun stubPrepared(
        leafMessageId: Long?,
        messages: List<ChatMessage> = listOf(TestDefaults.chatMessage1, TestDefaults.chatMessage2),
        resolvedCompaction: ResolvedCompactionConfig = enabledCompaction
    ) {
        coEvery { preparationService.prepareSessionRuntime(USER_ID, SESSION_ID) } returns PreparedConversationTurn(
            session = sessionOf(leafMessageId, messages),
            llmConfig = primaryConfig,
            resolvedCompaction = resolvedCompaction
        ).right()
        coEvery { conversationTurnPersistence.loadSessionToolCalls(SESSION_ID) } returns emptyList()
    }

    /** Captures the window [ConversationCompactionService.compactNow] is invoked with. */
    private class CompactNowCapture {
        /** Labeled summary text of the covered prefix, captured only by cases that expect one. */
        val summary = slot<String>()

        /** Uncompressed units handed to the forced compaction. */
        val units = slot<List<ConversationContextUnit>>()

        /** Cumulative identity ledger handed to the forced compaction. */
        val coveredSnapshots = slot<List<SourceMessageSnapshot>>()
    }

    /**
     * Stubs `compactNow` and captures the window it receives.
     *
     * @param capture Holder receiving the captured arguments.
     * @param result Outcome the mocked compaction returns.
     * @param hasSummary When true the stub expects a labeled prefix summary and captures it; otherwise it
     *            expects a null summary (a whole-thread window), which the stub then also enforces.
     * @param leafMessageId Thread leaf the stub expects as the verified-insert anchor.
     */
    private fun stubCompactNow(
        capture: CompactNowCapture,
        result: Either<ConversationCompactionError, ManualCompactionOutcome>,
        hasSummary: Boolean = false,
        leafMessageId: Long = LEAF_MESSAGE_ID
    ) {
        coEvery {
            compactionService.compactNow(
                USER_ID,
                SESSION_ID,
                effectiveSettings,
                primaryConfig,
                if (hasSummary) capture(capture.summary) else null,
                capture(capture.units),
                capture(capture.coveredSnapshots),
                leafMessageId
            )
        } returns result
    }

    @Test
    fun `whole thread without an eligible chunk is summarized as the full thread`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(LEAF_MESSAGE_ID, any(), any()) } returns
            contextOf(1L to t0, 2L to t1)
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns emptyList()
        val capture = CompactNowCapture()
        stubCompactNow(capture, ManualCompactionOutcome.Persisted(persistedChunk()).right())

        val outcome = service().compactThread(USER_ID, SESSION_ID).getOrNull()

        assertEquals(ManualCompactionOutcome.Persisted(persistedChunk()), outcome)
        assertEquals(listOf(1L, 2L), capture.units.captured.map { it.source.id })
        assertTrue(capture.coveredSnapshots.captured.isEmpty())
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `an eligible prefix chunk supplies the summary the tail and the ledger`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(LEAF_MESSAGE_ID, any(), any()) } returns
            contextOf(1L to t0, 2L to t1, 3L to t2)
        val prefixChunk = chunkOf(
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns listOf(prefixChunk)
        val capture = CompactNowCapture()
        stubCompactNow(capture, ManualCompactionOutcome.Persisted(persistedChunk()).right(), hasSummary = true)

        val outcome = service().compactThread(USER_ID, SESSION_ID).getOrNull()

        assertIs<ManualCompactionOutcome.Persisted>(outcome)
        assertEquals(
            effectiveSettings.summaryLabel + prefixChunk.summary,
            capture.summary.captured
        )
        assertEquals(listOf(3L), capture.units.captured.map { it.source.id })
        assertEquals(listOf(1L, 2L), capture.coveredSnapshots.captured.map { it.id })
        assertEquals(listOf(t0, t1), capture.coveredSnapshots.captured.map { it.updatedAt })
    }

    @Test
    fun `a fully covered thread is a no-op and triggers no auxiliary call`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(LEAF_MESSAGE_ID, any(), any()) } returns
            contextOf(1L to t0, 2L to t1, 3L to t2)
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns listOf(
            chunkOf(
                coverage = listOf(
                    CompactedMessageCoverage(0, 1L, t0),
                    CompactedMessageCoverage(1, 2L, t1),
                    CompactedMessageCoverage(2, 3L, t2)
                )
            )
        )

        val outcome = service().compactThread(USER_ID, SESSION_ID).getOrNull()

        assertEquals(ManualCompactionOutcome.Skipped(CompactionSkipReason.ALREADY_COMPACTED), outcome)
        coVerify(exactly = 0) {
            compactionService.compactNow(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a single-message thread is compactable`() = runTest {
        stubPrepared(leafMessageId = 1L, messages = listOf(TestDefaults.chatMessage1))
        coEvery { chatContextBuilder.buildContext(1L, any(), any()) } returns contextOf(1L to t0)
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns emptyList()
        val capture = CompactNowCapture()
        stubCompactNow(capture, ManualCompactionOutcome.Persisted(persistedChunk()).right(), leafMessageId = 1L)

        val outcome = service().compactThread(USER_ID, SESSION_ID).getOrNull()

        assertIs<ManualCompactionOutcome.Persisted>(outcome)
        assertEquals(listOf(1L), capture.units.captured.map { it.source.id })
    }

    @Test
    fun `a session without any message is a no-op`() = runTest {
        stubPrepared(leafMessageId = null, messages = emptyList())

        val outcome = service().compactThread(USER_ID, SESSION_ID).getOrNull()

        assertEquals(ManualCompactionOutcome.Skipped(CompactionSkipReason.NOTHING_TO_COMPACT), outcome)
        coVerify(exactly = 0) { chatContextBuilder.buildContext(any(), any(), any()) }
        coVerify(exactly = 0) {
            compactionService.compactNow(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `an unusable configuration fails with an invalid configuration`() = runTest {
        stubPrepared(
            leafMessageId = LEAF_MESSAGE_ID,
            resolvedCompaction = ResolvedCompactionConfig.Unusable(
                reason = "Compaction modelId is not set",
                automaticCompactionEnabled = true
            )
        )

        val error = assertIs<ConversationCompactionError.InvalidConfiguration>(
            service().compactThread(USER_ID, SESSION_ID).leftOrNull()
        )

        assertTrue(error.reason.contains("Compaction modelId is not set"))
        coVerify(exactly = 0) { chatContextBuilder.buildContext(any(), any(), any()) }
    }

    @Test
    fun `a failed runtime resolution fails with an invalid configuration carrying its reason`() = runTest {
        coEvery { preparationService.prepareSessionRuntime(USER_ID, SESSION_ID) } returns
            ValidateNewMessageError.ModelConfigurationError("No agent role selected for session $SESSION_ID").left()

        val error = assertIs<ConversationCompactionError.InvalidConfiguration>(
            service().compactThread(USER_ID, SESSION_ID).leftOrNull()
        )

        assertTrue(error.reason.contains("No agent role selected for session $SESSION_ID"))
    }

    @Test
    fun `an unbuildable thread fails with a source change`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(any(), any(), any()) } throws
            IllegalStateException("Cannot build context: cyclic parent chain detected at message 2")

        val error = assertIs<ConversationCompactionError.SourceChanged>(
            service().compactThread(USER_ID, SESSION_ID).leftOrNull()
        )

        assertTrue(error.reason.contains("cyclic parent chain"))
        coVerify(exactly = 0) {
            compactionService.compactNow(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `insufficient reduction propagates and nothing is persisted`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(LEAF_MESSAGE_ID, any(), any()) } returns
            contextOf(1L to t0, 2L to t1)
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns emptyList()
        val capture = CompactNowCapture()
        stubCompactNow(
            capture,
            ConversationCompactionError.InsufficientReduction(
                sourceTokenCount = 5_000L,
                resultTokenCount = 2_000L,
                thresholdTokens = 1_000L
            ).left()
        )

        val error = assertIs<ConversationCompactionError.InsufficientReduction>(
            service().compactThread(USER_ID, SESSION_ID).leftOrNull()
        )

        assertEquals(2_000L, error.resultTokenCount)
        // The service never persists on its own: only the shared one-shot compaction may write.
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `a summary that does not shrink its window is reported as a skip and persists nothing`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(LEAF_MESSAGE_ID, any(), any()) } returns
            contextOf(1L to t0, 2L to t1)
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns emptyList()
        val capture = CompactNowCapture()
        stubCompactNow(
            capture,
            ManualCompactionOutcome.Skipped(CompactionSkipReason.SUMMARY_NOT_SMALLER).right()
        )

        val outcome = service().compactThread(USER_ID, SESSION_ID).getOrNull()

        assertEquals(ManualCompactionOutcome.Skipped(CompactionSkipReason.SUMMARY_NOT_SMALLER), outcome)
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `cancellation during the auxiliary call propagates and nothing is persisted`() = runTest {
        stubPrepared(leafMessageId = LEAF_MESSAGE_ID)
        coEvery { chatContextBuilder.buildContext(LEAF_MESSAGE_ID, any(), any()) } returns
            contextOf(1L to t0, 2L to t1)
        coEvery { chunkDao.getChunksBySessionId(SESSION_ID) } returns emptyList()
        val capture = CompactNowCapture()
        coEvery {
            compactionService.compactNow(
                USER_ID,
                SESSION_ID,
                effectiveSettings,
                primaryConfig,
                null,
                capture(capture.units),
                capture(capture.coveredSnapshots),
                LEAF_MESSAGE_ID
            )
        } throws CancellationException("Compaction cancelled")

        val thrown = assertFailsWith<CancellationException> {
            service().compactThread(USER_ID, SESSION_ID)
        }

        assertEquals("Compaction cancelled", thrown.message)
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    /** Builds a retained chunk covering the first two messages of the test thread. */
    private fun chunkOf(
        id: Long = 40L,
        coverage: List<CompactedMessageCoverage> = listOf(
            CompactedMessageCoverage(0, 1L, t0),
            CompactedMessageCoverage(1, 2L, t1)
        )
    ): ConversationCompactionChunk = ConversationCompactionChunk(
        id = id,
        sessionId = SESSION_ID,
        summary = "prior summary",
        modelId = TestDefaults.llmModel1.id,
        settingsId = TestDefaults.modelSettings1.id,
        providerId = TestDefaults.llmProvider1.id,
        modelName = TestDefaults.llmModel1.name,
        settingsName = TestDefaults.modelSettings1.name,
        providerName = TestDefaults.llmProvider1.name,
        instruction = "Summarize faithfully",
        thresholdTokens = 1_000L,
        sourceTokenCount = 300L,
        resultTokenCount = 50L,
        tokenCounterVersion = "test-fixed-v1",
        coverageCount = coverage.size,
        createdAt = 9_000L,
        coverage = coverage
    )

    /** The chunk the mocked one-shot compaction reports as persisted. */
    private fun persistedChunk(): ConversationCompactionChunk = chunkOf(id = 55L)

    private companion object {
        /** Session under test. */
        const val SESSION_ID = 7L

        /** User requesting the compaction. */
        const val USER_ID = 1L

        /** Leaf message of the test session. */
        const val LEAF_MESSAGE_ID = 2L
    }
}
