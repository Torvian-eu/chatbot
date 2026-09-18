package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.toolCallArgumentLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallIterationLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallsPerStepLimitExceededCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers the streaming tool-call limits: the turn's iteration bound flags the final message while still executing
 * its calls, the per-step cap drops the whole batch, and the argument cap drops only the calls whose argument
 * exceeded it while the other calls of the step still run — each with the matching failure on the message and
 * exactly one [ConversationTurnEvent.TurnCompleted].
 */
class DefaultConversationTurnOrchestratorStreamingToolCallLimitTest :
    DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies that a response asking for more tool calls than one step accepts flags its assistant message, drops
     * the whole batch — including the calls within the cap — and ends the turn without executing anything.
     */
    @Test
    fun `processStreamingTurn flags the message when the per-step tool call cap is reached`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = streamingUserMessage(701L, "Many calls")
        val placeholder = streamingPlaceholder(702L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        // One call more than the cap allows: a batch that size means something went wrong with the response.
        val refusedIndex = ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            *toolCallChunks(indices = 0..refusedIndex).toTypedArray()
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Many calls", tools = listOf(toolDefinition))
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                toolCallsPerStepLimitExceededCompletionState(
                    ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP
                )
            )
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().last()
        assertEquals(AssistantMessageErrorCode.TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED, finished.assistantMessage.errorCode)
        // Only the accepted calls reached the live-UI stream; the refused one was dropped without a trace.
        assertEquals(
            ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP,
            events.count { it is ConversationTurnEvent.ToolCallDelta }
        )
        // The whole batch is dropped: no call of this step is persisted, approved or executed.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) }
        // The turn ends there, with a single terminal frame.
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that a tool call whose argument payload exceeds the per-call cap flags its assistant message and is
     * the only call that is dropped: the complete calls of the same response — the one before the cut and the one
     * after it — are persisted, approved and executed with their own arguments, in the order they were requested.
     */
    @Test
    fun `processStreamingTurn drops only the oversized argument call and runs the other calls`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = streamingUserMessage(711L, "Huge file")
        val placeholder = streamingPlaceholder(712L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val oversizedArguments = "x".repeat(ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS + 1)
        // The first call is complete, the second one exceeds the argument cap and the third one is complete again:
        // only the second call is refused, so the calls around it must still reach the tool layer.
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ToolCallChunk(
                index = 0,
                id = "call_search",
                name = toolDefinition.name,
                argumentsDelta = "{\"query\":\"docs\"}"
            ).right(),
            LLMStreamChunk.ToolCallChunk(
                index = 1,
                id = "call_write",
                name = "write_file",
                argumentsDelta = oversizedArguments
            ).right(),
            LLMStreamChunk.ToolCallChunk(
                index = 2,
                id = "call_after",
                name = "write_file",
                argumentsDelta = "{\"path\":\"after.txt\"}"
            ).right(),
            LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
            LLMStreamChunk.Done.right()
        )
        val pendingSearchCall = pendingToolCall(
            messageId = placeholder.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name
        )
        val pendingAfterCall = pendingToolCall(
            messageId = placeholder.id,
            toolDefinitionId = toolDefinition.id,
            toolName = "write_file",
            id = 2L,
            toolCallId = "call_after",
            input = "{\"path\":\"after.txt\"}"
        )
        val capturedRequests = mutableListOf<List<LLMCompletionResult.CompletionChoice.ToolCallRequest>>()
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                placeholder.id,
                capture(capturedRequests),
                listOf(toolDefinition)
            )
        } returns listOf(pendingSearchCall, pendingAfterCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(pendingSearchCall),
            ToolCallExecutionEvent.ToolCallCompleted(pendingAfterCall)
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Huge file", tools = listOf(toolDefinition))
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                toolCallArgumentLimitExceededCompletionState(
                    ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
                )
            )
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().last()
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED,
            finished.assistantMessage.errorCode
        )
        // Only the clipped call is refused: the calls before and after it are handed over with their own payloads
        // and in their original order, and the clipped call never reaches persistence (it is the only gap in the
        // persisted set).
        assertEquals(1, capturedRequests.size)
        assertEquals(listOf("call_search", "call_after"), capturedRequests.single().map { it.toolCallId })
        assertEquals(
            listOf("{\"query\":\"docs\"}", "{\"path\":\"after.txt\"}"),
            capturedRequests.single().map { it.arguments }
        )
        assertEquals(listOf(toolDefinition.name, "write_file"), capturedRequests.single().map { it.name })
        // The persisted set — which is what the client and any later replay see — holds exactly those two calls.
        val received = events.filterIsInstance<ConversationTurnEvent.ToolCallsReceived>().single()
        assertEquals(listOf(pendingSearchCall, pendingAfterCall), received.toolCalls)
        // Both survivors are executed and their results are reported...
        verify(exactly = 1) {
            toolCallOrchestrator.executeAndUpdateToolCalls(
                any(),
                listOf(pendingSearchCall, pendingAfterCall),
                any(),
                any(),
                any(),
                any()
            )
        }
        assertEquals(2, events.count { it is ConversationTurnEvent.ToolExecutionCompleted })
        // ...and the turn ends after them, without a follow-up LLM call and with a single terminal frame.
        assertIs<ConversationTurnEvent.ToolExecutionCompleted>(events[events.size - 2])
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        coVerify(exactly = 1) { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Verifies that a single clipped call leaves nothing to execute: the message is flagged with the argument-limit
     * failure, no tool call is persisted or executed, and the turn ends with one terminal frame.
     */
    @Test
    fun `processStreamingTurn flags the message and drops everything when the first call exceeds the cap`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = streamingUserMessage(771L, "Huge file only")
        val placeholder = streamingPlaceholder(772L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val oversizedArguments = "x".repeat(ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS + 1)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ToolCallChunk(
                index = 0,
                id = "call_write",
                name = "write_file",
                argumentsDelta = oversizedArguments
            ).right(),
            LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
            LLMStreamChunk.Done.right()
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Huge file only", tools = listOf(toolDefinition))
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                toolCallArgumentLimitExceededCompletionState(
                    ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
                )
            )
        }
        // The live delta is the clipped payload the cap allows, so the UI never renders more than the cap.
        val toolCallDelta = assertIs<ConversationTurnEvent.ToolCallDelta>(
            events.first { it is ConversationTurnEvent.ToolCallDelta }
        )
        assertEquals(
            ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS,
            toolCallDelta.argumentsDelta?.length
        )
        // The clipped call is not executed, and nothing else was requested either.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) }
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies the iteration bound: the last allowed iteration flags its own message with the iteration-limit
     * failure, its tool calls are still executed, and the turn ends afterwards — while every earlier iteration
     * keeps its completed message and its executed tool call.
     */    @Test
    fun `processStreamingTurn flags the final message when the tool calling bound is reached`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = streamingUserMessage(721L, "Agent loop")
        val firstMessageId = 800L
        var nextMessageId = firstMessageId
        val pendingToolCall = pendingToolCall(
            messageId = firstMessageId,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name
        )
        val expectedFailure = toolCallIterationLimitExceededCompletionState(
            ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Agent loop", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        // Every iteration persists its own placeholder: the loop runs one assistant message per iteration.
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            PersistedAssistantMessage(streamingPlaceholder(nextMessageId++, thirdArg()), userMessage)
        }
        // Every iteration answers with the same tool-calling response, so the loop only stops on its bound.
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ToolCallChunk(
                index = 0,
                id = "call_search",
                name = toolDefinition.name,
                argumentsDelta = "{\"query\":\"docs\"}"
            ).right(),
            LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
            LLMStreamChunk.Done.right()
        )
        coEvery { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) } returns
            listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        } returns flowOf(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall))

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Agent loop", tools = listOf(toolDefinition))
        ).toList()

        val finalMessageId = firstMessageId + ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS - 1
        // The message of the final allowed iteration carries the failure...
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(finalMessageId, "", expectedFailure)
        }
        assertEquals(
            ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS.toLong(),
            nextMessageId - firstMessageId
        )
        // ...while every earlier iteration completed normally and kept its executed tool call.
        coVerify(exactly = ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS - 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                any(),
                "",
                AssistantMessageCompletionState.Completed
            )
        }
        // The bound does not suppress execution: every iteration, including the final one, ran its tool call.
        assertEquals(
            ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS,
            events.count { it is ConversationTurnEvent.ToolCallsReceived }
        )
        coVerify(exactly = ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS) {
            conversationTurnPersistence.persistPendingToolCalls(any(), any(), any())
        }
        verify(exactly = ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().last()
        assertEquals(finalMessageId, finished.assistantMessage.id)
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALL_ITERATION_LIMIT_EXCEEDED,
            finished.assistantMessage.errorCode
        )
        // The turn ends after the calls of the final iteration have run, with a single terminal frame.
        assertIs<ConversationTurnEvent.ToolExecutionCompleted>(events[events.size - 2])
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that a clip applied to streamed deltas is not reported when the provider's authoritative payload
     * for the same call fits the cap: the authoritative payload replaces the accumulated one, so the step
     * completes normally and keeps its tool call instead of failing the message.
     */
    @Test
    fun `processStreamingTurn keeps a step clean when the authoritative payload replaces a clipped delta`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = streamingUserMessage(761L, "Authoritative arguments")
        val firstPlaceholder = streamingPlaceholder(762L, userMessage.id)
        val secondPlaceholder = streamingPlaceholder(764L, firstPlaceholder.id)
        stubStreamingTurnStart(userMessage, firstPlaceholder)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                firstPlaceholder.id,
                testModel,
                testSettings.copy(stream = true),
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.InFlight
            )
        } returns PersistedAssistantMessage(secondPlaceholder, firstPlaceholder)
        val oversizedDelta = "x".repeat(ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS + 100)
        val authoritativeArguments = "{\"path\":\"notes.txt\",\"content\":\"hi\"}"
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            // The deltas alone exceed the cap, but the provider then corrects the payload for this call.
            flowOf(
                LLMStreamChunk.ToolCallChunk(
                    index = 0,
                    id = "call_write",
                    name = "write_file",
                    argumentsDelta = oversizedDelta
                ).right(),
                LLMStreamChunk.ToolCallDone(
                    index = 0,
                    id = "call_write",
                    name = "write_file",
                    arguments = authoritativeArguments
                ).right(),
                LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right(),
                LLMStreamChunk.Done.right()
            ),
            flowOf(
                LLMStreamChunk.ContentChunk("Done", finishReason = "stop").right(),
                LLMStreamChunk.Done.right()
            )
        )
        val pendingToolCall = pendingToolCall(
            messageId = firstPlaceholder.id,
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name
        )
        val capturedRequests = mutableListOf<List<LLMCompletionResult.CompletionChoice.ToolCallRequest>>()
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                firstPlaceholder.id,
                capture(capturedRequests),
                listOf(toolDefinition)
            )
        } returns listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        } returns flowOf(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall))

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Authoritative arguments", tools = listOf(toolDefinition))
        ).toList()

        // The first iteration completed normally (no cap failure) and kept its tool call...
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                firstPlaceholder.id,
                "",
                AssistantMessageCompletionState.Completed
            )
        }
        assertEquals(1, capturedRequests.size)
        assertEquals(authoritativeArguments, capturedRequests.single().single().arguments)
        assertEquals("call_write", capturedRequests.single().single().toolCallId)
        // ...and the turn ended with exactly one terminal frame.
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Builds the enabled tool definition used by the limit scenarios.
     *
     * @return A deterministic enabled tool definition of the shared test session.
     */
    private fun searchToolDefinition(): LocalMCPToolDefinition = LocalMCPToolDefinition(
        id = 8L,
        name = "search",
        description = "Searches docs",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { },
        outputSchema = null,
        isEnabled = true,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        serverId = 1L,
        mcpToolName = "search"
    )

    /**
     * Builds one tool-calling response that streams a call per [indices] entry and then completes.
     *
     * @param indices Output indices the streamed tool calls are emitted for.
     * @return Chunks of the response, in provider order.
     */
    private fun toolCallChunks(indices: IntRange): List<Either<LLMCompletionError, LLMStreamChunk>> =
        buildList {
            indices.forEach { index ->
                add(
                    LLMStreamChunk.ToolCallChunk(
                        index = index,
                        id = "call_$index",
                        name = "search",
                        argumentsDelta = "{\"query\":\"$index\"}"
                    ).right()
                )
            }
            add(LLMStreamChunk.ContentChunk("", finishReason = "tool_calls").right())
            add(LLMStreamChunk.Done.right())
        }

    /**
     * Builds the persisted tool call the tool orchestrator reports as completed.
     *
     * @param messageId Assistant message the call belongs to.
     * @param toolDefinitionId Tool definition the call was resolved from.
     * @param toolName Name of the executed tool.
     * @param id Identifier of the persisted tool-call record.
     * @param toolCallId Provider tool-call identifier the call was requested with.
     * @param input Arguments payload the call was requested with.
     * @return A pending tool call as returned by the persistence collaborator.
     */
    private fun pendingToolCall(
        messageId: Long,
        toolDefinitionId: Long,
        toolName: String,
        id: Long = 1L,
        toolCallId: String = "call_search",
        input: String = "{\"query\":\"docs\"}"
    ): ToolCall = ToolCall(
        id = id,
        messageId = messageId,
        toolDefinitionId = toolDefinitionId,
        toolName = toolName,
        toolCallId = toolCallId,
        input = input,
        output = null,
        status = ToolCallStatus.PENDING,
        executedAt = baseInstant
    )
}
