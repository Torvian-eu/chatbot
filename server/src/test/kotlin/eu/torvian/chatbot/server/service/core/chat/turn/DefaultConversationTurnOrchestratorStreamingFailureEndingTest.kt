package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.streamInterruptedCompletionState
import eu.torvian.chatbot.server.service.llm.unexpectedFailureCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the endings of a streaming turn — provider errors, missing terminal chunks, unexpected throws — and the
 * first-ending rule that decides between them.
 */
class DefaultConversationTurnOrchestratorStreamingFailureEndingTest : DefaultConversationTurnOrchestratorStreamingTestBase() {

    /**
     * Verifies the streaming failure path: the partial text and the failure state are persisted, the failure
     * is only reported through the message bubble plus the unchanged transient notification, and the turn closes
     * with exactly one terminal frame after the finalized message.
     */
    @Test
    fun `processStreamingTurn persists failure state and partial content when the stream reports an error`() = runTest {
        val userMessage = streamingUserMessage(511L, "Fail me")
        val placeholder = streamingPlaceholder(512L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val authenticationError = LLMCompletionError.AuthenticationError("invalid api key")
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.Error(authenticationError).right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Fail me")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.AUTHENTICATION_FAILED,
                    message = "The provider rejected the API key or credentials."
                )
            )
        }
        // Documented failure ordering: the transient error notification, the finalized (failed) message, and
        // then exactly one terminal frame.
        assertEquals(6, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[2])
        val errorEvent = assertIs<ConversationTurnEvent.ExternalServiceError>(events[3])
        assertEquals(authenticationError, errorEvent.llmError)
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals("Partial answer", finished.assistantMessage.content)
        assertFalse(finished.assistantMessage.isComplete)
        assertEquals(AssistantMessageIncompleteCause.FAILED, finished.assistantMessage.incompleteCause)
        assertEquals(AssistantMessageErrorCode.AUTHENTICATION_FAILED, finished.assistantMessage.errorCode)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])
    }

    /**
     * Verifies that a provider stream which ends without any terminal chunk is recorded as a stream
     * interruption with the text received so far, and that the turn still closes.
     */
    @Test
    fun `processStreamingTurn records a stream ended without terminal chunk as interrupted`() = runTest {
        val userMessage = streamingUserMessage(521L, "Half a stream")
        val placeholder = streamingPlaceholder(522L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        // No `Done`, no error chunk and no cancellation: the stream simply ends early.
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Half an answer").right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Half a stream")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Half an answer",
                streamInterruptedCompletionState()
            )
        }
        assertEquals(5, events.size)
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[3])
        assertEquals(AssistantMessageErrorCode.STREAM_INTERRUPTED, finished.assistantMessage.errorCode)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[4])
        // No new transient error frame is introduced for this ending.
        assertTrue(events.none { it is ConversationTurnEvent.ExternalServiceError })
    }

    /**
     * Verifies that a stream ending in a provider-declared failure is recorded with the classification the
     * provider code maps to — never with the generic stream-interruption fallback — while the partial content,
     * the transient notification and the documented event order stay exactly as for any other failure ending.
     */
    @Test
    fun `processStreamingTurn records a provider declared failure instead of a stream interruption`() = runTest {
        val userMessage = streamingUserMessage(531L, "Provider failed")
        val placeholder = streamingPlaceholder(532L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val providerFailure = LLMCompletionError.ProviderFailureError(
            providerCode = "server_error",
            message = "The provider ended the response without completing it."
        )
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.Error(providerFailure).right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Provider failed")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
                    message = "The provider failed to generate a response."
                )
            )
        }
        // Exactly one terminal write: the failure is reported through the error chunk, not through the
        // "stream ended without a terminal chunk" classification.
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
        assertEquals(6, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        assertIs<ConversationTurnEvent.AssistantMessageStarted>(events[1])
        assertIs<ConversationTurnEvent.AssistantMessageDelta>(events[2])
        val errorEvent = assertIs<ConversationTurnEvent.ExternalServiceError>(events[3])
        assertEquals(providerFailure, errorEvent.llmError)
        val finished = assertIs<ConversationTurnEvent.AssistantMessageFinished>(events[4])
        assertEquals("Partial answer", finished.assistantMessage.content)
        assertFalse(finished.assistantMessage.isComplete)
        assertEquals(AssistantMessageIncompleteCause.FAILED, finished.assistantMessage.incompleteCause)
        assertNotEquals(
            AssistantMessageErrorCode.STREAM_INTERRUPTED,
            finished.assistantMessage.errorCode,
            "A provider-declared failure must not be reported as a dropped stream"
        )
        assertEquals(AssistantMessageErrorCode.PROVIDER_UNAVAILABLE, finished.assistantMessage.errorCode)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[5])
    }

    /**
     * Verifies that a generation the provider stopped on its content policy is persisted as a rejection carrying the
     * text received so far, with exactly one write, one transient frame and one terminal frame.
     */
    @Test
    fun `processStreamingTurn records a content-policy stop as a rejection that keeps the content`() = runTest {
        val userMessage = streamingUserMessage(601L, "Filtered")
        val placeholder = streamingPlaceholder(602L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val contentPolicyStop = LLMCompletionError.ProviderFailureError(
            providerCode = "content_filter",
            message = "The provider ended the response without completing it."
        )
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partially written answer").right(),
            LLMStreamChunk.Error(contentPolicyStop).right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Filtered")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partially written answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
                    message = "The provider stopped the response because of its content policy."
                )
            )
        }
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
        val finished = events.filterIsInstance<ConversationTurnEvent.AssistantMessageFinished>().single()
        assertFalse(finished.assistantMessage.isComplete)
        assertEquals(1, events.count { it is ConversationTurnEvent.ExternalServiceError })
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies the unexpected-failure path: a non-cancellation exception leaves the partial text persisted as
     * an unexpected failure, the finalized message is still delivered through the live stream (its socket is
     * alive), and the exception is rethrown so the chat service can still report a turn-level error.
     */
    @Test
    fun `processStreamingTurn records an unexpected failure and rethrows when the stream throws`() = runTest {
        val userMessage = streamingUserMessage(551L, "Boom")
        val placeholder = streamingPlaceholder(552L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(LLMStreamChunk.ContentChunk("Partial answer").right())
            throw IllegalStateException("provider flow exploded")
        }

        val events = mutableListOf<ConversationTurnEvent>()
        val thrown = runCatching {
            orchestrator.processStreamingTurn(streamingTurnRequest("Boom")).collect { events.add(it) }
        }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                unexpectedFailureCompletionState()
            )
        }
        // The turn is closed by ChatServiceImpl (UnexpectedError + StreamCompleted) after the rethrow, so the
        // orchestrator must not emit a terminal frame here.
        assertTrue(events.none { it == ConversationTurnEvent.TurnCompleted })
        // The durable write above is not enough: the unexpected failure has a live socket, so the finalized
        // message must also reach the client through the stream instead of being re-read by a later request.
        assertTrue(events.any { it is ConversationTurnEvent.AssistantMessageFinished })
    }

    /**
     * Verifies the exactly-once rule: an exception raised after the message was already finalized must not
     * re-flag the completed answer as a failure, even though the unexpected-failure handler runs.
     */
    @Test
    fun `processStreamingTurn keeps a finalized message completed when the stream throws afterwards`() = runTest {
        val userMessage = streamingUserMessage(561L, "Late boom")
        val placeholder = streamingPlaceholder(562L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(LLMStreamChunk.ContentChunk("Complete answer", finishReason = "stop").right())
            emit(LLMStreamChunk.Done.right())
            // The provider flow fails only after the terminal chunk was already delivered and persisted.
            throw IllegalStateException("late provider failure")
        }

        val thrown = runCatching {
            orchestrator.processStreamingTurn(streamingTurnRequest("Late boom")).toList()
        }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Complete answer",
                AssistantMessageCompletionState.Completed
            )
        }
        // The finalizer is a no-op once the message reached a terminal state, so nothing is re-flagged.
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
    }

    /**
     * Verifies the first-ending rule against a provider that reports an error and completes afterwards anyway:
     * the failure owns the outcome, so the partial text and the failure state are persisted, and the late
     * completion adds neither a second write nor a completed message.
     */
    @Test
    fun `processStreamingTurn keeps the failure when a completion follows an error chunk`() = runTest {
        val userMessage = streamingUserMessage(571L, "Error then done")
        val placeholder = streamingPlaceholder(572L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.Error(LLMCompletionError.ApiError(502, "upstream error", null)).right(),
            // A completion after the error is a provider/proxy anomaly and must change nothing.
            LLMStreamChunk.Done.right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Error then done")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
                    message = "The provider is currently unavailable (HTTP 502)."
                )
            )
        }
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
        // The failure is reported once, and the turn closes once.
        val errorFrame = assertIs<ConversationTurnEvent.ExternalServiceError>(
            events.single { it is ConversationTurnEvent.ExternalServiceError }
        )
        assertEquals(502, assertIs<LLMCompletionError.ApiError>(errorFrame.llmError).statusCode)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        assertTrue(
            events.none { it is ConversationTurnEvent.AssistantMessageFinished && it.assistantMessage.isComplete },
            "A completion that follows the error signal must not complete the message"
        )
    }

    /**
     * Verifies that a completed stream is not changed by what follows its terminal chunk: the finished message
     * keeps its content, and a late delta and a late error are dropped without a frame or a second write.
     */
    @Test
    fun `processStreamingTurn drops late content and errors after the provider completed`() = runTest {
        val userMessage = streamingUserMessage(581L, "Done then anomalies")
        val placeholder = streamingPlaceholder(582L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Complete answer", finishReason = "stop").right(),
            LLMStreamChunk.Done.right(),
            LLMStreamChunk.ContentChunk(" Late").right(),
            LLMStreamChunk.Error(LLMCompletionError.ApiError(500, "late failure", null)).right(),
            LLMStreamChunk.Done.right()
        )

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Done then anomalies")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Complete answer",
                AssistantMessageCompletionState.Completed
            )
        }
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
        // The late delta never reaches the client and the late error adds no transient frame.
        assertEquals(1, events.count { it is ConversationTurnEvent.AssistantMessageDelta })
        assertTrue(events.none { it is ConversationTurnEvent.ExternalServiceError })
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that a dialect parse failure is itself the ending of the stream: the message is persisted as an
     * unprocessable response with the text received before the failure, and a completion that follows it is
     * ignored instead of marking the message completed.
     */
    @Test
    fun `processStreamingTurn records a parse failure and ignores the completion that follows`() = runTest {
        val userMessage = streamingUserMessage(591L, "Unparseable then done")
        val placeholder = streamingPlaceholder(592L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val parseFailure = LLMCompletionError.InvalidResponseError("Failed to parse Responses streaming JSON chunk")
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(parseFailure.left())
            emit(LLMStreamChunk.ContentChunk("Late content").right())
            emit(LLMStreamChunk.Done.right())
        }

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Unparseable then done")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.INVALID_PROVIDER_RESPONSE,
                    message = "The provider returned a response that could not be processed."
                )
            )
        }
        coVerify(exactly = 1) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
        assertEquals(1, events.count { it is ConversationTurnEvent.ExternalServiceError })
        assertTrue(events.none { it is ConversationTurnEvent.AssistantMessageDelta })
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        assertTrue(
            events.none { it is ConversationTurnEvent.AssistantMessageFinished && it.assistantMessage.isComplete },
            "A parse failure must not be turned into a completed message by a later terminal event"
        )
    }

    /**
     * Verifies that a stream reporting two errors is reported to the client exactly once, with the cause of the
     * signal that ended it.
     */
    @Test
    fun `processStreamingTurn reports the first error signal of a stream exactly once`() = runTest {
        val userMessage = streamingUserMessage(595L, "Two error signals")
        val placeholder = streamingPlaceholder(596L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val parseFailure = LLMCompletionError.InvalidResponseError("Failed to parse Responses streaming JSON chunk")
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(parseFailure.left())
            emit(LLMStreamChunk.Error(LLMCompletionError.AuthenticationError("invalid api key")).right())
        }

        val events = orchestrator.processStreamingTurn(streamingTurnRequest("Two error signals")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.INVALID_PROVIDER_RESPONSE,
                    message = "The provider returned a response that could not be processed."
                )
            )
        }
        val frames = events.filterIsInstance<ConversationTurnEvent.ExternalServiceError>()
        assertEquals(1, frames.size, "A stream has one ending, so it is reported once")
        assertEquals(parseFailure, frames.single().llmError)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }

    /**
     * Verifies that a provider truncation reaches the message as the provider output-limit failure with the text
     * received before the cut, and that the tool calls of that step are neither persisted nor executed.
     */
    @Test
    fun `processStreamingTurn persists a provider truncation with its partial content and runs no tool`() = runTest {
        val toolDefinition = LocalMCPToolDefinition(
            id = 10L,
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
        val userMessage = streamingUserMessage(597L, "Truncated")
        val placeholder = streamingPlaceholder(598L, userMessage.id)
        stubStreamingTurnStart(userMessage, placeholder)
        val truncation = LLMCompletionError.ProviderFailureError(
            providerCode = "length",
            message = "The provider ended the response without completing it."
        )
        coEvery { llmApiClient.completeChatStreaming(any(), any(), any(), any(), any(), any()) } returns flowOf(
            LLMStreamChunk.ContentChunk("Partial answer").right(),
            LLMStreamChunk.ToolCallChunk(
                index = 0,
                id = "call-truncated",
                name = toolDefinition.name,
                argumentsDelta = "{}"
            ).right(),
            LLMStreamChunk.Error(truncation).right()
        )

        val events = orchestrator.processStreamingTurn(
            streamingTurnRequest("Truncated", tools = listOf(toolDefinition))
        ).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.updateAssistantMessageContent(
                placeholder.id,
                "Partial answer",
                AssistantMessageCompletionState.failed(
                    code = AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
                    message = "The provider stopped the response because the model reached its output limit."
                )
            )
        }
        val errorFrame = assertIs<ConversationTurnEvent.ExternalServiceError>(
            events.single { it is ConversationTurnEvent.ExternalServiceError }
        )
        assertEquals(truncation, errorFrame.llmError)
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
    }
}
