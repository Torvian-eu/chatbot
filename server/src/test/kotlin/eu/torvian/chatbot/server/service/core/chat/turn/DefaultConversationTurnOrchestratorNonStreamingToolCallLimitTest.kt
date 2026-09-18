package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.core.toolcall.ToolCallExecutionEvent
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.service.llm.toolCallArgumentLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallIterationLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.toolCallsPerStepLimitExceededCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the non-streaming tool-call limits: the turn's iteration bound flags the final message while still handing
 * its calls over for execution, the per-step cap drops the whole batch, and the argument cap drops only the calls
 * whose argument exceeded it while the other calls of the step still run — each with the matching failure on the
 * message and exactly one [ConversationTurnEvent.TurnCompleted].
 */
class DefaultConversationTurnOrchestratorNonStreamingToolCallLimitTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies that a response asking for more tool calls than one step accepts persists a failed assistant
     * message, drops the whole batch — including the calls within the cap — and ends the turn without executing
     * anything.
     */
    @Test
    fun `processNonStreamingTurn flags the message when the per-step tool call cap is reached`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = userMessage(731L, "Many calls")
        // One call more than the cap allows: a batch that size means something went wrong with the response.
        val toolCallRequests = (0..ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP).map { index ->
            LLMCompletionResult.CompletionChoice.ToolCallRequest(
                name = toolDefinition.name,
                arguments = "{\"query\":\"$index\"}",
                toolCallId = "call_$index"
            )
        }
        val completion = completion(content = "Calling many tools.", toolCalls = toolCallRequests)
        val expectedFailure = toolCallsPerStepLimitExceededCompletionState(
            ConversationTurnLimits.MAX_TOOL_CALLS_PER_STEP
        )
        val failedAssistantMessage = assistantMessage(
            id = 732L,
            parentMessageId = userMessage.id,
            content = "Calling many tools.",
            completion = expectedFailure
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Many calls", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Calling many tools.",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(failedAssistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            turnRequest(content = "Many calls", tools = listOf(toolDefinition))
        ).toList()

        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        val saved = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED,
            saved.assistantMessage.errorCode
        )
        // The whole batch is dropped: no call of this step is persisted, approved or executed.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
        // The turn ends there, without a follow-up LLM call, and with a single terminal frame.
        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that a tool call whose argument payload exceeds the per-call cap persists a failed assistant message
     * and is the only call that is dropped: the complete calls of the same response — the one before the cut and the
     * one after it — are handed over for execution with their own arguments, in the order they were requested.
     */
    @Test
    fun `processNonStreamingTurn drops only the oversized argument call and runs the other calls`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = userMessage(741L, "Huge file")
        val oversizedArguments = "x".repeat(ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS + 1)
        // The first call is complete, the second one exceeds the argument cap and the third one is complete again:
        // only the second call is refused, so the calls around it must still reach the tool layer.
        val completion = completion(
            content = "Writing the file.",
            toolCalls = listOf(
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = toolDefinition.name,
                    arguments = "{\"query\":\"docs\"}",
                    toolCallId = "call_search"
                ),
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = "write_file",
                    arguments = oversizedArguments,
                    toolCallId = "call_write"
                ),
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = "write_file",
                    arguments = "{\"path\":\"after.txt\"}",
                    toolCallId = "call_after"
                )
            )
        )
        val expectedFailure = toolCallArgumentLimitExceededCompletionState(
            ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
        )
        val failedAssistantMessage = assistantMessage(
            id = 742L,
            parentMessageId = userMessage.id,
            content = "Writing the file.",
            completion = expectedFailure
        )
        val pendingSearchCall = pendingToolCall(
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name
        )
        val pendingAfterCall = pendingToolCall(
            toolDefinitionId = toolDefinition.id,
            toolName = "write_file",
            id = 2L,
            toolCallId = "call_after",
            input = "{\"path\":\"after.txt\"}"
        )
        val capturedRequests = mutableListOf<List<LLMCompletionResult.CompletionChoice.ToolCallRequest>>()

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Huge file", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Writing the file.",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(failedAssistantMessage, userMessage)
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                failedAssistantMessage.id,
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

        val events = orchestrator.processNonStreamingTurn(
            turnRequest(content = "Huge file", tools = listOf(toolDefinition))
        ).toList()

        val saved = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED,
            saved.assistantMessage.errorCode
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
        coVerify(exactly = 1) {
            conversationTurnPersistence.persistPendingToolCalls(
                failedAssistantMessage.id,
                any(),
                listOf(toolDefinition)
            )
        }
        // The persisted set — which is what the client and any later replay see — holds exactly those two calls.
        val received = events.filterIsInstance<ConversationTurnEvent.ToolCallsReceived>().single()
        assertEquals(listOf(pendingSearchCall, pendingAfterCall), received.toolCalls)
        // Both survivors are executed and their results are reported...
        assertEquals(2, events.count { it is ConversationTurnEvent.ToolExecutionCompleted })
        // ...and the turn ends after them, without a follow-up LLM call and with a single terminal frame.
        assertIs<ConversationTurnEvent.ToolExecutionCompleted>(events[events.size - 2])
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Verifies that a single clipped call leaves nothing to execute: the message is flagged with the argument-limit
     * failure, no tool call is persisted or executed, and the turn ends with one terminal frame.
     */
    @Test
    fun `processNonStreamingTurn flags the message and drops everything when the first call exceeds the cap`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = userMessage(771L, "Huge file only")
        val oversizedArguments = "x".repeat(ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS + 1)
        val completion = completion(
            content = "Writing the file.",
            toolCalls = listOf(
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = "write_file",
                    arguments = oversizedArguments,
                    toolCallId = "call_write"
                )
            )
        )
        val expectedFailure = toolCallArgumentLimitExceededCompletionState(
            ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
        )
        val failedAssistantMessage = assistantMessage(
            id = 772L,
            parentMessageId = userMessage.id,
            content = "Writing the file.",
            completion = expectedFailure
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Huge file only", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Writing the file.",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(failedAssistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            turnRequest(content = "Huge file only", tools = listOf(toolDefinition))
        ).toList()

        val saved = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED,
            saved.assistantMessage.errorCode
        )
        // The clipped call is not executed, and nothing else was requested either.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 1) { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) }
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies the iteration bound for the non-streaming path: the last allowed iteration persists its message with
     * the iteration-limit failure, its tool calls are still executed, and the turn ends afterwards — while every
     * earlier iteration keeps its completed message and its executed tool call.
     */
    @Test
    fun `processNonStreamingTurn flags the final message when the tool calling bound is reached`() = runTest {
        val toolDefinition = searchToolDefinition()
        val userMessage = userMessage(751L, "Agent loop")
        val firstMessageId = 900L
        var nextMessageId = firstMessageId
        val pendingToolCall = pendingToolCall(toolDefinitionId = toolDefinition.id, toolName = toolDefinition.name)
        val completion = completion(
            content = "Working on it.",
            toolCalls = listOf(
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = toolDefinition.name,
                    arguments = "{\"query\":\"docs\"}",
                    toolCallId = pendingToolCall.toolCallId
                )
            )
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Agent loop", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        // Every iteration answers with the same tool-calling response, so the loop only stops on its bound.
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        // The completion state the step writes is captured by parameter position, so the failure state of the final
        // iteration is asserted by the message the step hands to the loop (a call that relies on Kotlin default
        // arguments appends a mask and marker to the recorded invocation, so the state cannot be read positionally
        // from the end of the argument list).
        val capturedCompletionStates = mutableListOf<AssistantMessageCompletionState>()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                capture(capturedCompletionStates)
            )
        } coAnswers {
            PersistedAssistantMessage(
                assistantMessage(
                    id = nextMessageId++,
                    parentMessageId = thirdArg(),
                    content = secondArg(),
                    completion = capturedCompletionStates.last()
                ),
                userMessage
            )
        }
        coEvery { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) } returns
            listOf(pendingToolCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        } returns flowOf(ToolCallExecutionEvent.ToolCallCompleted(pendingToolCall))

        val events = orchestrator.processNonStreamingTurn(
            turnRequest(content = "Agent loop", tools = listOf(toolDefinition))
        ).toList()

        val finalMessageId = firstMessageId + ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS - 1
        val expectedFailure = toolCallIterationLimitExceededCompletionState(
            ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS
        )
        // The final allowed iteration records the failure in the very statement that creates its message.
        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Working on it.",
                finalMessageId - 1,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        }
        assertEquals(
            ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS.toLong(),
            nextMessageId - firstMessageId
        )
        // Every earlier iteration completed normally and persisted its own tool call.
        coVerify(exactly = ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS - 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Working on it.",
                any(),
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.Completed
            )
        }
        // The bound does not drop calls: every iteration, including the final one, ran its tool call.
        coVerify(exactly = ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS) {
            conversationTurnPersistence.persistPendingToolCalls(any(), any(), any())
        }
        verify(exactly = ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
        assertEquals(
            ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS,
            events.count { it is ConversationTurnEvent.ToolCallsReceived }
        )
        val savedEvents = events.filterIsInstance<ConversationTurnEvent.AssistantMessageSaved>()
        assertEquals(ConversationTurnLimits.MAX_TOOL_CALLING_ITERATIONS, savedEvents.size)
        assertEquals(finalMessageId, savedEvents.last().assistantMessage.id)
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALL_ITERATION_LIMIT_EXCEEDED,
            savedEvents.last().assistantMessage.errorCode
        )
        // The turn ends after the calls of the final iteration have run, with a single terminal frame.
        assertIs<ConversationTurnEvent.ToolExecutionCompleted>(events[events.size - 2])
        assertEquals(ConversationTurnEvent.TurnCompleted, events.last())
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies the call/result pairing a clipped step leaves behind for the replayed context: the flagged message
     * owns exactly the calls that were executed (the two complete calls around the clipped one), and the next
     * turn's request replays one assistant tool call per tool result with matching provider ids — no dangling call
     * for the clipped request, no orphan result.
     */
    @Test
    fun `processNonStreamingTurn replays only the paired calls and results after a middle clip`() = runTest {
        val toolDefinition = searchToolDefinition()
        val firstUserMessage = userMessage(801L, "Huge file")
        val oversizedArguments = "x".repeat(ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS + 1)
        val completionWithClip = completion(
            content = "Writing the file.",
            toolCalls = listOf(
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = toolDefinition.name,
                    arguments = "{\"query\":\"docs\"}",
                    toolCallId = "call_search"
                ),
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = "write_file",
                    arguments = oversizedArguments,
                    toolCallId = "call_write"
                ),
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = "write_file",
                    arguments = "{\"path\":\"after.txt\"}",
                    toolCallId = "call_after"
                )
            )
        )
        val expectedFailure = toolCallArgumentLimitExceededCompletionState(
            ConversationTurnLimits.MAX_TOOL_CALL_ARGUMENT_CHARS
        )
        val flaggedAssistantMessage = assistantMessage(
            id = 802L,
            parentMessageId = firstUserMessage.id,
            content = "Writing the file.",
            completion = expectedFailure
        )
        // The rows the persistence collaborator wrote for the surviving calls, which are the only calls of the step
        // that exist under the flagged message (the clipped call was never handed to it).
        val storedSearchCall = pendingToolCall(
            toolDefinitionId = toolDefinition.id,
            toolName = toolDefinition.name
        ).copy(messageId = flaggedAssistantMessage.id)
        val storedAfterCall = pendingToolCall(
            toolDefinitionId = toolDefinition.id,
            toolName = "write_file",
            id = 2L,
            toolCallId = "call_after",
            input = "{\"path\":\"after.txt\"}"
        ).copy(messageId = flaggedAssistantMessage.id)
        val completedToolCalls = listOf(
            storedSearchCall.copy(output = "{\"results\":[]}", status = ToolCallStatus.SUCCESS),
            storedAfterCall.copy(output = "{\"written\":true}", status = ToolCallStatus.SUCCESS)
        )
        val secondUserMessage = userMessage(803L, "Next question")
            .copy(parentMessageId = flaggedAssistantMessage.id)
        val secondAssistantMessage = assistantMessage(
            id = 804L,
            parentMessageId = secondUserMessage.id,
            content = "Here you go.",
            completion = AssistantMessageCompletionState.Completed
        )
        val capturedContexts = mutableListOf<List<RawChatMessage>>()

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Huge file", null, any())
        } returns PersistedUserMessage(firstUserMessage, null)
        coEvery {
            conversationTurnPersistence.saveUserMessage(
                testSession.id,
                "Next question",
                flaggedAssistantMessage.id,
                any()
            )
        } returns PersistedUserMessage(secondUserMessage, flaggedAssistantMessage)
        // The first turn starts without tool calls; the second one loads what the clipped step left behind.
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returnsMany
            listOf(emptyList(), completedToolCalls)
        coEvery {
            llmApiClient.completeChat(capture(capturedContexts), any(), any(), any(), any(), any())
        } returnsMany listOf(
            completionWithClip.right(),
            completion(content = "Here you go.", toolCalls = emptyList(), finishReason = "stop").right()
        )
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Writing the file.",
                firstUserMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(flaggedAssistantMessage, firstUserMessage)
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Here you go.",
                secondUserMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = AssistantMessageCompletionState.Completed
            )
        } returns PersistedAssistantMessage(secondAssistantMessage, secondUserMessage)
        coEvery {
            conversationTurnPersistence.persistPendingToolCalls(
                flaggedAssistantMessage.id,
                any(),
                listOf(toolDefinition)
            )
        } returns listOf(storedSearchCall, storedAfterCall)
        every {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        } returns flowOf(
            ToolCallExecutionEvent.ToolCallCompleted(completedToolCalls[0]),
            ToolCallExecutionEvent.ToolCallCompleted(completedToolCalls[1])
        )

        val clippedEvents = orchestrator.processNonStreamingTurn(
            turnRequest(content = "Huge file", tools = listOf(toolDefinition))
        ).toList()
        assertEquals(
            AssistantMessageErrorCode.TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED,
            clippedEvents.filterIsInstance<ConversationTurnEvent.AssistantMessageSaved>()
                .single().assistantMessage.errorCode
        )
        assertEquals(ConversationTurnEvent.TurnCompleted, clippedEvents.last())

        orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession.copy(
                    messages = listOf(firstUserMessage, flaggedAssistantMessage)
                ),
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", listOf(toolDefinition)),
                content = secondUserMessage.content,
                parentMessageId = flaggedAssistantMessage.id,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The replayed thread is the whole branch of the flagged message, expanded into paired calls and results.
        assertEquals(2, capturedContexts.size)
        val replayed = capturedContexts[1]
        assertEquals(listOf("user", "assistant", "tool", "tool", "user"), replayed.map { it.role })
        val replayedAssistant = assertIs<RawChatMessage.Assistant>(replayed[1])
        val replayedCalls = assertNotNull(replayedAssistant.toolCalls)
        val replayedResults = replayed.filterIsInstance<RawChatMessage.Tool>()
        assertEquals(listOf("call_search", "call_after"), replayedCalls.map { it.id })
        assertEquals(replayedCalls.map { it.id }, replayedResults.map { it.toolCallId })
        assertEquals(
            listOf("{\"query\":\"docs\"}", "{\"path\":\"after.txt\"}"),
            replayedCalls.map { it.arguments }
        )
        assertEquals(listOf("{\"results\":[]}", "{\"written\":true}"), replayedResults.map { it.content })
        // The clipped call was never persisted, so it has neither a replayed call nor a result.
        assertTrue(replayedCalls.none { it.id == "call_write" })
        assertTrue(replayedResults.none { it.toolCallId == "call_write" })
    }

    /**
     * Builds an enabled tool definition for the limit scenarios.
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
     * Builds a user message fixture of the shared test session.
     *
     * @param id Message identifier.
     * @param content Message content.
     * @return User message belonging to the shared [testSession].
     */
    private fun userMessage(id: Long, content: String): ChatMessage.UserMessage = ChatMessage.UserMessage(
        id = id,
        sessionId = testSession.id,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    /**
     * Builds a non-streaming turn request on the shared session/model/provider fixtures.
     *
     * @param content User content of the turn.
     * @param tools Enabled tools for the turn.
     * @return Turn request wired to the shared test fixtures.
     */
    private fun turnRequest(content: String, tools: List<LocalMCPToolDefinition>): ConversationTurnRequest =
        ConversationTurnRequest(
            userId = 1L,
            session = testSession,
            llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", tools),
            content = content,
            parentMessageId = null,
            fileReferences = emptyList(),
            toolApprovalFlow = emptyFlow(),
            operatorToolResultFlow = emptyFlow(),
            turnControlSignal = TurnControlSignal()
        )

    /**
     * Builds a non-streaming completion with a single tool-calling choice.
     *
     * @param content Assistant content of the choice.
     * @param toolCalls Tool calls the choice requests.
     * @param finishReason Finish reason reported by the provider for the choice.
     * @return Completion result as returned by the LLM client.
     */
    private fun completion(
        content: String,
        toolCalls: List<LLMCompletionResult.CompletionChoice.ToolCallRequest>,
        finishReason: String = "tool_calls"
    ): LLMCompletionResult = LLMCompletionResult(
        id = "completion-limits",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = content,
                finishReason = finishReason,
                index = 0,
                toolCalls = toolCalls
            )
        ),
        usage = LLMCompletionResult.UsageStats(1, 1, 2),
        metadata = emptyMap()
    )

    /**
     * Builds the persisted assistant message a step returns for the given completion state.
     *
     * @param id Message identifier.
     * @param parentMessageId Parent the message replies to.
     * @param content Persisted content.
     * @param completion Completion state the step wrote together with the content.
     * @return Assistant message carrying the given state.
     */
    private fun assistantMessage(
        id: Long,
        parentMessageId: Long,
        content: String,
        completion: AssistantMessageCompletionState
    ): ChatMessage.AssistantMessage = ChatMessage.AssistantMessage(
        id = id,
        sessionId = testSession.id,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = parentMessageId,
        childrenMessageIds = emptyList(),
        modelId = testModel.id,
        settingsId = testSettings.id,
        isComplete = completion.isComplete,
        incompleteCause = completion.incompleteCause,
        errorCode = completion.errorCode,
        errorMessage = completion.errorMessage
    )

    /**
     * Builds the persisted tool call the tool orchestrator reports as completed.
     *
     * @param toolDefinitionId Tool definition the call was resolved from.
     * @param toolName Name of the executed tool.
     * @param id Identifier of the persisted tool-call record.
     * @param toolCallId Provider tool-call identifier the call was requested with.
     * @param input Arguments payload the call was requested with.
     * @return A pending tool call as returned by the persistence collaborator.
     */
    private fun pendingToolCall(
        toolDefinitionId: Long,
        toolName: String,
        id: Long = 1L,
        toolCallId: String = "call_search",
        input: String = "{\"query\":\"docs\"}"
    ): ToolCall = ToolCall(
        id = id,
        messageId = 1L,
        toolDefinitionId = toolDefinitionId,
        toolName = toolName,
        toolCallId = toolCallId,
        input = input,
        output = null,
        status = ToolCallStatus.PENDING,
        executedAt = baseInstant
    )
}
