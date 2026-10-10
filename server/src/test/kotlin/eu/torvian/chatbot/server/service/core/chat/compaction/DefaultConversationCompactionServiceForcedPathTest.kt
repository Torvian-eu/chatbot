package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.core.CompactionSkipReason
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.server.data.dao.error.ConversationCompactionChunkDaoError
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContext
import eu.torvian.chatbot.server.service.core.chat.context.ConversationContextUnit
import eu.torvian.chatbot.server.service.core.chat.context.SourceMessageSnapshot
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Forced-compaction cases of [DefaultConversationCompactionService].
 *
 * Exercises the auxiliary summarization call and its failures, the timeout bound, persistence and
 * verification failures, the resolved-threshold overrides and the explicit compactNow outcomes.
 */
class DefaultConversationCompactionServiceForcedPathTest : DefaultConversationCompactionServiceTestFixture() {

    @Test
    fun `auxiliary call is tool-free keeps reasoning and appends the instruction as the final user message`() =
        runTest {
            stubRetainedChunks()
            stubCounter()
            // The resolved auxiliary config has an empty system message; the instruction comes from
            // the settings, not from the config.
            coEvery { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()

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
        coEvery { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()
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
        coEvery { auxiliaryConfigResolver.resolveAuxiliaryConfig(1L, effectiveSettings) } returns primaryConfig.right()
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
        val state = assertIs<CompactionTurnState.Active>(
            service.beginTurn(
                1L, 7L, context.units,
                enabledUsable(effectiveSettings.copy(thresholdTokens = 12_000L))
            )
        )

        val preflight = assertNotNull(
            service.preparePrimaryContext(state, primaryConfig, expectedLeafMessageId = 2L).getOrNull()
        )

        assertEquals(12_000L, state.settings.thresholdTokens)
        assertEquals(context.flatten(), preflight.primaryMessages)
        assertNull(preflight.persistedChunkIfAny)
        coVerify(exactly = 0) { auxiliaryConfigResolver.resolveAuxiliaryConfig(any(), any()) }
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
        val state = assertIs<CompactionTurnState.Active>(
            service.beginTurn(
                1L, 7L, context.units,
                enabledUsable(raisedSettings.copy(thresholdTokens = 12_000L))
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

    @Test
    fun `compactNow persists when the summary is smaller than the window it replaces`() = runTest {
        stubCounter(unitTokens = 5_000L, summaryTokens = 50L)
        stubAuxiliarySuccess()
        coEvery { chunkDao.insertVerifiedChunk(any(), 2L) } returns persistedChunk(id = 55L).right()

        val outcome = service().compactNow(
            userId = 1L,
            sessionId = 7L,
            settings = effectiveSettings,
            primaryConfig = primaryConfig,
            summary = null,
            units = contextOf(1L to t0, 2L to t1).units,
            coveredSnapshots = emptyList(),
            expectedLeafMessageId = 2L
        ).getOrNull()

        assertEquals(55L, assertIs<ManualCompactionOutcome.Persisted>(outcome).chunk.id)
        coVerify(exactly = 1) { chunkDao.insertVerifiedChunk(any(), 2L) }
    }

    @Test
    fun `compactNow skips and persists nothing when the summary does not shrink its window`() = runTest {
        // A forced compaction far below the threshold: the generated summary counts exactly as much as
        // the single unit it replaces, so the boundary counts as no reduction.
        val belowThresholdSettings = effectiveSettings.copy(thresholdTokens = 100_000L)
        stubCounter(unitTokens = 5_000L, summaryTokens = 5_000L)
        stubAuxiliarySuccess(compactionSettings = belowThresholdSettings)

        val outcome = service().compactNow(
            userId = 1L,
            sessionId = 7L,
            settings = belowThresholdSettings,
            primaryConfig = primaryConfig,
            summary = null,
            units = contextOf(1L to t0).units,
            coveredSnapshots = emptyList(),
            expectedLeafMessageId = 1L
        ).getOrNull()

        assertEquals(ManualCompactionOutcome.Skipped(CompactionSkipReason.SUMMARY_NOT_SMALLER), outcome)
        // The summary was generated but is discarded: nothing is persisted and no candidate is built.
        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { chunkDao.insertVerifiedChunk(any(), any()) }
    }
}
