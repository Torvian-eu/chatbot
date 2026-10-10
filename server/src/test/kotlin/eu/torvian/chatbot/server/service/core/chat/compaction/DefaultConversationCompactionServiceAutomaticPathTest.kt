package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Automatic rolling-window cases of [DefaultConversationCompactionService].
 *
 * Covers window seeding, the disabled-arming branch, threshold and count behaviour, the
 * compaction-required decisions and the configuration/reduction failures that keep the originals.
 */
class DefaultConversationCompactionServiceAutomaticPathTest : DefaultConversationCompactionServiceTestFixture() {

    @Test
    fun `inactive state sends the original flattened thread`() = runTest {
        val context = contextOf(1L to t0, 2L to t1)

        val state = service().beginTurn(1L, 7L, context.units, unusableDisabled)
        val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { tokenCounter.countPrimaryInput(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unusable configuration returns the inactive state and never compacts`() = runTest {
        val context = contextOf(1L to t0, 2L to t1)

        val state = service().beginTurn(1L, 7L, context.units, unusableDisabled)
        assertIs<CompactionTurnState.Inactive>(state)
        // An unusable turn mirrors the absent-row path: retained chunks are never loaded because
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
            val deletedRowsConfig = enabledUsable(deletedRows)
            stubRetainedChunks()
            stubCounter() // 2-unit thread = 10,000 tokens > 1,000 threshold -> compaction required
            coEvery { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, deletedRows) } returns
                ConversationCompactionError.InvalidConfiguration("Compaction model 99 not found").left()

            val state = service().beginTurn(1L, 7L, contextOf(1L to t0, 2L to t1).units, deletedRowsConfig)
            assertIs<CompactionTurnState.Active>(state)

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
            enabledUsable(deletedRows)
        )
        assertIs<CompactionTurnState.Active>(state)
        val preflight =
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).getOrNull()
        assertNotNull(preflight)
        assertEquals(contextOf(1L to t0, 2L to t1).flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        // The thread never exceeded the threshold, so no compaction and no configuration error.
        coVerify(exactly = 0) { auxiliaryConfigResolver.resolveAuxiliaryConfig(any(), any()) }
    }

    @Test
    fun `below-threshold thread with no eligible chunk sends the originals`() = runTest {
        stubRetainedChunks()
        stubCounter(unitTokens = 100L) // below threshold
        val context = contextOf(1L to t0, 2L to t1)

        val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
        val preflight = service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L)
            .getOrNull()
        assertNotNull(preflight)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { auxiliaryConfigResolver.resolveAuxiliaryConfig(any(), any()) }
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `below-threshold thread with an eligible chunk sends the summary instead of the raw thread`() = runTest {
        stubRetainedChunks()
        // The raw thread (3 x 100) fits the threshold, but the eligible prefix chunk is the intended
        // primary context, so the window is its summary plus the uncovered tail.
        stubCounter(unitTokens = 100L, summaryTokens = 50L)
        val prefixChunk = chunkOf(
            id = 40L,
            createdAt = 1_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(prefixChunk)

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0, 2L to t1, 3L to t2).units, defaultResolvedCompaction)
        val preflight = assertNotNull(
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L).getOrNull()
        )

        assertEquals(2, preflight.primaryMessages.size)
        assertEquals(
            "prior summary 40",
            (preflight.primaryMessages[0] as RawChatMessage.User).content.removePrefix(effectiveSettings.summaryLabel)
        )
        assertEquals("m3", (preflight.primaryMessages[1] as RawChatMessage.User).content)
        assertNull(preflight.persistedChunkIfAny)
        // Reuse only: nothing is summarized and nothing is persisted.
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { auxiliaryConfigResolver.resolveAuxiliaryConfig(any(), any()) }
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `a turn with automatic compaction disabled still injects an eligible summary without counting`() = runTest {
        // Automatic compaction is off, but the stored configuration is usable: the window is still
        // seeded from the eligible chunk and the result is sent unchanged, with no auxiliary call.
        stubCounter(unitTokens = 5_000L, summaryTokens = 50L)
        val prefixChunk = chunkOf(
            id = 40L,
            createdAt = 1_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(prefixChunk)

        val state = service().beginTurn(
            1L, 7L, contextOf(1L to t0, 2L to t1, 3L to t2).units, disabledUsable(effectiveSettings)
        )
        val active = assertIs<CompactionTurnState.Active>(state)
        assertFalse(active.automaticCompactionEnabled)

        val preflight = assertNotNull(
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L).getOrNull()
        )

        assertEquals(2, preflight.primaryMessages.size)
        assertEquals(
            "prior summary 40",
            (preflight.primaryMessages[0] as RawChatMessage.User).content.removePrefix(effectiveSettings.summaryLabel)
        )
        assertEquals("m3", (preflight.primaryMessages[1] as RawChatMessage.User).content)
        assertNull(preflight.persistedChunkIfAny)
        // Automatic compaction is disabled: the threshold is never consulted and no auxiliary call is
        // made.
        coVerify(exactly = 0) { tokenCounter.countPrimaryInput(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { auxiliaryConfigResolver.resolveAuxiliaryConfig(any(), any()) }
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `the largest eligible chunk wins when several chunks are eligible`() = runTest {
        stubRetainedChunks()
        stubCounter(unitTokens = 100L, summaryTokens = 50L)
        // Both chunks are eligible; the one covering two messages supersedes the one-message chunk.
        val smaller = chunkOf(
            id = 40L,
            createdAt = 1_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0))
        )
        val larger = chunkOf(
            id = 41L,
            createdAt = 2_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(smaller, larger)

        val state = service().beginTurn(1L, 7L, contextOf(1L to t0, 2L to t1, 3L to t2).units, defaultResolvedCompaction)
        val preflight = assertNotNull(
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 3L).getOrNull()
        )

        val active = state as CompactionTurnState.Active
        assertEquals(listOf(1L, 2L), active.coveredSnapshots.map { it.id })
        assertEquals(listOf(3L), active.units.map { it.source.id })
        assertEquals(
            "prior summary 41",
            (preflight.primaryMessages[0] as RawChatMessage.User).content.removePrefix(effectiveSettings.summaryLabel)
        )
    }

    @Test
    fun `an ineligible chunk leaves the window on the raw thread even below the threshold`() = runTest {
        stubRetainedChunks()
        stubCounter(unitTokens = 100L, summaryTokens = 50L)
        // Message 2 was edited after the chunk was created, so its recorded timestamp no longer matches.
        val staleChunk = chunkOf(
            id = 40L,
            createdAt = 1_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(staleChunk)

        val context = contextOf(1L to t0, 2L to t2)
        val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
        val preflight = assertNotNull(
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).getOrNull()
        )

        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }

    @Test
    fun `over-threshold seeded window compacts the summary plus tail into a cumulative chunk`() = runTest {
        stubRetainedChunks()
        // Seeded window = [summary] (100) + the two uncovered units (2 x 800) = 1700 > 1000.
        stubCounter(unitTokens = 800L, summaryTokens = 100L)
        stubAuxiliarySuccess()
        val prefixChunk = chunkOf(
            id = 40L,
            createdAt = 1_000L,
            coverage = listOf(CompactedMessageCoverage(0, 1L, t0), CompactedMessageCoverage(1, 2L, t1))
        )
        coEvery { chunkDao.getChunksBySessionId(7L) } returns listOf(prefixChunk)
        val candidates = mutableListOf<ConversationCompactionChunkCandidate>()
        coEvery { chunkDao.insertVerifiedChunk(capture(candidates), 4L) } returns persistedChunk(id = 55L).right()
        val capturedInput = mutableListOf<List<RawChatMessage>>()
        coEvery {
            llmApiClient.completeChat(capture(capturedInput), any(), any(), any(), any(), any(), any())
        } returns completionWith("Rolled up.").right()

        val context = contextOf(1L to t0, 2L to t1, 3L to t2, 4L to t3)
        val state = service().beginTurn(1L, 7L, context.units, defaultResolvedCompaction)
        val preflight = assertNotNull(
            service().preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 4L).getOrNull()
        )

        // One auxiliary call on the seeded window, with the instruction appended as the last message.
        assertEquals(
            listOf(effectiveSettings.summaryLabel + "prior summary 40", "m3", "m4", "Summarize faithfully"),
            capturedInput.single().map { it.content }
        )
        // Coverage is cumulative: the seeded ledger extended by the summarized tail.
        assertEquals(listOf(1L, 2L, 3L, 4L), candidates.single().coverage.map { it.messageId })
        assertEquals(1_700L, candidates.single().sourceTokenCount)
        // The window collapses to the new summary alone.
        assertEquals(1, preflight.primaryMessages.size)
        assertEquals(55L, preflight.persistedChunkIfAny?.id)
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

            // The service updated its own state: the window is the summary only, and the ledger holds the
            // compacted identities content-free.
            val active = state as CompactionTurnState.Active
            assertTrue(active.units.isEmpty())
            assertEquals(summary, active.summaryMessage)
            assertEquals(listOf(1L, 2L), active.coveredSnapshots.map { it.id })
            assertEquals(listOf(t0, t1), active.coveredSnapshots.map { it.updatedAt })

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
            enabledUsable(customSettings)
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

            val active = state as CompactionTurnState.Active
            // The window is the seeded summary plus the delta only; the full thread content was released.
            assertEquals(listOf(3L), active.units.map { it.source.id })
            // The ledger is seeded from the eligible chunk's persisted coverage, content-free.
            assertEquals(listOf(1L, 2L), active.coveredSnapshots.map { it.id })
            assertEquals(listOf(t0, t1), active.coveredSnapshots.map { it.updatedAt })

            // Hybrid reuse: [summary] + additional messages sent as-is, no auxiliary call, no persistence.
            assertEquals(2, preflight.primaryMessages.size)
            assertTrue(
                (preflight.primaryMessages[0] as RawChatMessage.User).content.startsWith(effectiveSettings.summaryLabel)
            )
            assertEquals("m3", (preflight.primaryMessages[1] as RawChatMessage.User).content)
            assertNull(preflight.persistedChunkIfAny)
            coVerify(exactly = 0) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { auxiliaryConfigResolver.resolveAuxiliaryConfig(any(), any()) }
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
        coEvery { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()
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
            coVerify(exactly = 1) { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, effectiveSettings) }
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

            val active = state as CompactionTurnState.Active
            assertEquals(listOf(1L, 2L, 3L, 4L), active.coveredSnapshots.map { it.id })
            assertTrue(active.units.isEmpty())
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
            val active = state as CompactionTurnState.Active
            assertNull(active.summaryMessage)
            assertEquals(1, active.units.size)
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
                enabledUsable(effectiveSettings.copy(thresholdTokens = 800L))
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
}
