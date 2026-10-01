package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.streamInterruptedCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the measured provider-call duration of a streaming assistant step: which ending carries it, that it is
 * stored by the single terminal write of the step, and that each assistant message of a tool-calling turn records
 * its own value.
 */
class DefaultConversationTurnOrchestratorStreamingResponseDurationTest :
    DefaultConversationTurnOrchestratorStreamingTestBase() {

    /** Durations passed to the terminal write of each finalized message, in call order. */
    private val capturedResponseDurations = mutableListOf<Long?>()

    /** Clears the captured durations so an assertion never sees a previous test's write. */
    @BeforeEach
    fun resetCapturedResponseDurations() {
        capturedResponseDurations.clear()
    }

    /**
     * Stubs the terminal write of a streaming step so the measured duration is captured, returning a message that
     * mirrors the written state.
     */
    private fun stubFinalizedWriteCapturingDuration() {
        coEvery {
            conversationTurnPersistence.updateAssistantMessageContent(
                any(),
                any(),
                any(),
                usageStats = any(),
                responseDurationMs = any()
            )
        } answers {
            val completion = thirdArg<AssistantMessageCompletionState>()
            capturedResponseDurations.add(arg(4))
            ChatMessage.AssistantMessage(
                id = firstArg(),
                sessionId = testSession.id,
                content = secondArg(),
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = testSettings.id,
                isComplete = completion.isComplete,
                incompleteCause = completion.incompleteCause,
                errorCode = completion.errorCode,
                errorMessage = completion.errorMessage
            )
        }
    }

    /**
     * Verifies a completed stream stores the duration of its provider call together with the reported usage.
     */
    @Test
    fun `processStreamingTurn records the duration of a completed provider call`() = runTest {
        val userMessage = streamingUserMessage(701L, "Hello")
        val placeholder = streamingPlaceholder(702L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        stubFinalizedWriteCapturingDuration()
        val reportedUsage = UsageStats(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Hello there").right(),
            LLMStreamChunk.FinalUsageStats(reportedUsage).right(),
            LLMStreamChunk.Done.right()
        )

        orchestrator.processStreamingTurn(streamingTurnRequest("Hello")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "A completed step must record a duration")
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Hello there",
                AssistantMessageCompletionState.Completed,
                usageStats = reportedUsage,
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies a provider error ending still stores the duration of the attempt it terminated.
     */
    @Test
    fun `processStreamingTurn records the duration of a stream that ended with a provider error`() = runTest {
        val userMessage = streamingUserMessage(703L, "Fail")
        val placeholder = streamingPlaceholder(704L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        stubFinalizedWriteCapturingDuration()
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.Error(LLMCompletionError.AuthenticationError("invalid api key")).right()
        )

        orchestrator.processStreamingTurn(streamingTurnRequest("Fail")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "A failed attempt must record a duration")
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.AUTHENTICATION_FAILED,
                    message = "The provider rejected the API key or credentials."
                ),
                usageStats = null,
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies a user-interrupted stream stores the duration of the attempt it stopped.
     */
    @Test
    fun `processStreamingTurn records the duration of a user-interrupted attempt`() = runTest {
        val turnControlSignal = TurnControlSignal()
        val userMessage = streamingUserMessage(705L, "Stop me")
        val placeholder = streamingPlaceholder(706L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        stubFinalizedWriteCapturingDuration()
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(LLMStreamChunk.ContentChunk("Partial answer").right())
            // The stop is observed between two chunks, so neither the later content nor the terminal chunk lands.
            turnControlSignal.cancel()
            emit(LLMStreamChunk.ContentChunk(" never delivered").right())
            emit(LLMStreamChunk.Done.right())
        }

        orchestrator.processStreamingTurn(streamingTurnRequest("Stop me", turnControlSignal)).toList()

        val duration = assertNotNull(
            capturedResponseDurations.single(),
            "A stopped attempt must record the duration it ran for"
        )
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.InterruptedByUser,
                usageStats = null,
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies a stream that ends without any terminal signal stores the duration of its attempt.
     */
    @Test
    fun `processStreamingTurn records the duration of a stream that ended without a terminal signal`() = runTest {
        val userMessage = streamingUserMessage(707L, "Nothing arrives")
        val placeholder = streamingPlaceholder(708L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        stubFinalizedWriteCapturingDuration()
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns emptyFlow()

        orchestrator.processStreamingTurn(streamingTurnRequest("Nothing arrives")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "An unfinished attempt must be measured")
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                streamInterruptedCompletionState(),
                usageStats = null,
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies each assistant message of a tool-calling turn records the duration of its own provider call.
     */
    @Test
    fun `processStreamingTurn records one duration per assistant message of a tool-calling turn`() = runTest {
        val toolDefinition = LocalMCPToolDefinition(
            id = 8L,
            name = "lookup",
            description = "Looks something up",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { },
            outputSchema = null,
            isEnabled = true,
            createdAt = baseInstant,
            updatedAt = baseInstant,
            serverId = 1L,
            mcpToolName = "lookup"
        )
        val userMessage = streamingUserMessage(709L, "Look it up")
        val assistantToolStarted = ChatMessage.AssistantMessage(
            id = 710L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id,
            isComplete = false
        )
        val assistantFinal = assistantToolStarted.copy(
            id = 712L,
            parentMessageId = assistantToolStarted.id,
            content = "Done"
        )
        val pendingToolCall = ToolCall(
            id = 711L,
            messageId = assistantToolStarted.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name,
            toolCallId = "call_lookup",
            input = "{\"key\":\"a\"}",
            output = null,
            status = ToolCallStatus.PENDING,
            executedAt = baseInstant
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, userMessage.content, null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                userMessage.id,
                testModel,
                testSettings.copy(stream = true),
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight,
                usageStats = any(),
                responseDurationMs = any()
            )
        } returns PersistedAssistantMessage(assistantToolStarted, userMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                assistantToolStarted.id,
                testModel,
                testSettings.copy(stream = true),
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight,
                usageStats = any(),
                responseDurationMs = any()
            )
        } returns PersistedAssistantMessage(assistantFinal, assistantToolStarted)
        stubFinalizedWriteCapturingDuration()
        // The first iteration requests a tool call, the second answers with plain content and ends the loop.
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                LLMStreamChunk.ToolCallChunk(
                    index = 0,
                    id = "call_lookup",
                    name = "lookup",
                    argumentsDelta = "{\"key\":\"a\"}"
                ).right(),
                LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
                LLMStreamChunk.Done.right()
            ),
            flowOf(
                LLMStreamChunk.ContentChunk("Done", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
        )
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(assistantToolStarted.id, any(), listOf(toolDefinition))
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(
                ToolCallExecutionContext(
                    userId = 1L,
                    sessionId = 1L,
                    sessionName = "Session",
                    agentRoleId = testRoleId
                ),
                listOf(pendingToolCall),
                listOf(toolDefinition),
                any(),
                any(),
                any()
            )
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(
                pendingToolCall.copy(
                    output = "{\"results\":[]}",
                    status = ToolCallStatus.SUCCESS,
                    durationMs = 5L
                )
            )
        )

        orchestrator.processStreamingTurn(
            streamingTurnRequest("Look it up", tools = listOf(toolDefinition))
        ).toList()

        // One write per assistant message, each carrying its own measurement: nothing aggregates across steps.
        assertEquals(2, capturedResponseDurations.size, "Each assistant message of the turn must be finalized once")
        capturedResponseDurations.forEach { duration ->
            assertNotNull(duration, "Every assistant message of the turn must carry its own duration")
            assertTrue(duration >= 0, "A measured duration is never negative")
        }
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                assistantToolStarted.id,
                "",
                AssistantMessageCompletionState.Completed,
                usageStats = any(),
                responseDurationMs = capturedResponseDurations[0]
            )
        }
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                assistantFinal.id,
                "Done",
                AssistantMessageCompletionState.Completed,
                usageStats = any(),
                responseDurationMs = capturedResponseDurations[1]
            )
        }
    }

    /**
     * Verifies the measured window really wraps the provider call and that its unit is milliseconds, by blocking
     * the call for a real, non-virtual pause and asserting a lower bound on the captured value.
     */
    @Test
    fun `processStreamingTurn measures the blocked provider call in milliseconds`() = runTest {
        val userMessage = streamingUserMessage(713L, "Slow answer")
        val placeholder = streamingPlaceholder(714L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        stubFinalizedWriteCapturingDuration()
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            // A blocking pause, deliberately not a `delay` (virtual time would skip it).
            Thread.sleep(BLOCKING_CALL_MILLIS)
            emit(LLMStreamChunk.ContentChunk("Slow answer").right())
            emit(LLMStreamChunk.Done.right())
        }
            .flowOn(Dispatchers.IO)

        orchestrator.processStreamingTurn(streamingTurnRequest("Slow answer")).toList()

        val duration = assertNotNull(capturedResponseDurations.single())
        assertTrue(
            duration >= MINIMUM_EXPECTED_MILLIS,
            "A call blocked for $BLOCKING_CALL_MILLIS ms must be measured as at least $MINIMUM_EXPECTED_MILLIS ms, " +
                    "but was $duration ms"
        )
    }

    private companion object {
        /** Real pause the blocking-stub test imposes on the provider call. */
        const val BLOCKING_CALL_MILLIS = 25L

        /** Lowest captured value the blocked call must produce, tolerating scheduling overhead. */
        const val MINIMUM_EXPECTED_MILLIS = 20L
    }
}
