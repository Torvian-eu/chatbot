package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.toAssistantMessageCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Covers the assistant message persisted when a non-streaming LLM call yields no usable completion, and the partial message of a provider-declared ending. */
class DefaultConversationTurnOrchestratorNonStreamingFailureTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies that a failed non-streaming call creates an empty assistant message carrying the failure state,
     * which reaches the client before the transient error notification and the terminal frame.
     */
    @Test
    fun `processNonStreamingTurn creates an empty failed assistant message when the LLM call fails`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 601L,
            sessionId = testSession.id,
            content = "Fail me",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val authenticationError = LLMCompletionError.AuthenticationError("invalid api key")
        val expectedFailure = authenticationError.toAssistantMessageCompletionState()
        val failedAssistantMessage = ChatMessage.AssistantMessage(
            id = 602L,
            sessionId = testSession.id,
            content = "",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = userMessage.id,
            childrenMessageIds = emptyList(),
            modelId = testModel.id,
            settingsId = testSettings.id,
            isComplete = expectedFailure.isComplete,
            incompleteCause = expectedFailure.incompleteCause,
            errorCode = expectedFailure.errorCode,
            errorMessage = expectedFailure.errorMessage
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Fail me", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns
                authenticationError.left()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(failedAssistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Fail me",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(4, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        val savedFailedStep = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals("", savedFailedStep.assistantMessage.content)
        assertFalse(savedFailedStep.assistantMessage.isComplete)
        assertEquals(AssistantMessageIncompleteCause.FAILED, savedFailedStep.assistantMessage.incompleteCause)
        assertEquals(AssistantMessageErrorCode.AUTHENTICATION_FAILED, savedFailedStep.assistantMessage.errorCode)
        val errorEvent = assertIs<ConversationTurnEvent.ExternalServiceError>(events[2])
        assertEquals(authenticationError, errorEvent.llmError)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
    }

    /**
     * Verifies that a successful provider response without any completion choice is reported as the same empty
     * failed assistant message, because the message-level reason is the only durable trace of the failure.
     */
    @Test
    fun `processNonStreamingTurn creates an empty failed assistant message when the provider returns no choice`() =
        runTest {
            val userMessage = ChatMessage.UserMessage(
                id = 603L,
                sessionId = testSession.id,
                content = "Empty response",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
            val expectedFailure = LLMCompletionError.InvalidResponseError(
                "LLM API returned success but no completion choices."
            ).toAssistantMessageCompletionState()
            val failedAssistantMessage = ChatMessage.AssistantMessage(
                id = 604L,
                sessionId = testSession.id,
                content = "",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = userMessage.id,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = testSettings.id,
                isComplete = expectedFailure.isComplete,
                incompleteCause = expectedFailure.incompleteCause,
                errorCode = expectedFailure.errorCode,
                errorMessage = expectedFailure.errorMessage
            )
            val emptyCompletion = LLMCompletionResult(
                id = "completion-empty",
                choices = emptyList(),
                usage = LLMCompletionResult.UsageStats(1, 1, 2),
                metadata = emptyMap()
            )

            coEvery {
                conversationTurnPersistence.saveUserMessage(testSession.id, "Empty response", null, any())
            } returns PersistedUserMessage(userMessage, null)
            coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
            coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns emptyCompletion.right()
            coEvery {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id,
                    "",
                    userMessage.id,
                    testModel,
                    testSettings,
                    agentRoleId = testRoleId,
                    reasoningItems = null,
                    completion = expectedFailure
                )
            } returns PersistedAssistantMessage(failedAssistantMessage, userMessage)

            val events = orchestrator.processNonStreamingTurn(
                ConversationTurnRequest(
                    userId = 1L,
                    session = testSession,
                    llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                    content = "Empty response",
                    parentMessageId = null,
                    fileReferences = emptyList(),
                    toolApprovalFlow = emptyFlow(),
                    operatorToolResultFlow = emptyFlow(),
                    turnControlSignal = TurnControlSignal()
                )
            ).toList()

            assertEquals(4, events.size)
            val savedFailedStep = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
            assertEquals(AssistantMessageErrorCode.INVALID_PROVIDER_RESPONSE, savedFailedStep.assistantMessage.errorCode)
            assertIs<ConversationTurnEvent.ExternalServiceError>(events[2])
            assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
        }

    /**
     * Verifies that a response whose generation the provider declared cut off is persisted with the partial text it
     * still carried and the mapped failure state, reported once as a transient error, and that the tool calls of
     * that step are neither persisted nor executed.
     */
    @Test
    fun `processNonStreamingTurn persists the partial content of a declared ending and drops its tool calls`() =
        runTest {
            val toolDefinition = LocalMCPToolDefinition(
                id = 9L,
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
            val userMessage = ChatMessage.UserMessage(
                id = 605L,
                sessionId = testSession.id,
                content = "Long question",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
            val partialContent = "Partial answer: the model ran out of room"
            val providerFailure = LLMCompletionError.ProviderFailureError(
                providerCode = "max_output_tokens",
                message = "The provider ended the response without completing it."
            )
            val expectedFailure = providerFailure.toAssistantMessageCompletionState()
            val partialAssistantMessage = ChatMessage.AssistantMessage(
                id = 606L,
                sessionId = testSession.id,
                content = partialContent,
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = userMessage.id,
                childrenMessageIds = emptyList(),
                modelId = testModel.id,
                settingsId = testSettings.id,
                isComplete = expectedFailure.isComplete,
                incompleteCause = expectedFailure.incompleteCause,
                errorCode = expectedFailure.errorCode,
                errorMessage = expectedFailure.errorMessage
            )
            val cutOffCompletion = LLMCompletionResult(
                id = "completion-incomplete",
                choices = listOf(
                    LLMCompletionResult.CompletionChoice(
                        role = "assistant",
                        content = partialContent,
                        finishReason = "tool_calls",
                        index = 0,
                        toolCalls = listOf(
                            LLMCompletionResult.CompletionChoice.ToolCallRequest(
                                name = toolDefinition.name,
                                arguments = "{\"query\":\"docs\"}",
                                toolCallId = "call-incomplete"
                            )
                        )
                    )
                ),
                usage = LLMCompletionResult.UsageStats(1, 1, 2),
                providerFailure = providerFailure
            )

            coEvery {
                conversationTurnPersistence.saveUserMessage(testSession.id, "Long question", null, any())
            } returns PersistedUserMessage(userMessage, null)
            coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
            coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns
                    cutOffCompletion.right()
            coEvery {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id,
                    partialContent,
                    userMessage.id,
                    testModel,
                    testSettings,
                    agentRoleId = testRoleId,
                    reasoningItems = null,
                    completion = expectedFailure
                )
            } returns PersistedAssistantMessage(partialAssistantMessage, userMessage)

            val events = orchestrator.processNonStreamingTurn(
                ConversationTurnRequest(
                    userId = 1L,
                    session = testSession,
                    llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", listOf(toolDefinition)),
                    content = "Long question",
                    parentMessageId = null,
                    fileReferences = emptyList(),
                    toolApprovalFlow = emptyFlow(),
                    operatorToolResultFlow = emptyFlow(),
                    turnControlSignal = TurnControlSignal()
                )
            ).toList()

            // One write carrying content plus state, one transient error frame, one terminal frame.
            assertEquals(4, events.size)
            assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
            val savedPartialStep = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
            assertEquals(partialContent, savedPartialStep.assistantMessage.content)
            assertFalse(savedPartialStep.assistantMessage.isComplete)
            assertEquals(AssistantMessageIncompleteCause.FAILED, savedPartialStep.assistantMessage.incompleteCause)
            assertEquals(
                AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
                savedPartialStep.assistantMessage.errorCode
            )
            assertEquals(providerFailure, assertIs<ConversationTurnEvent.ExternalServiceError>(events[2]).llmError)
            assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
            coVerify(exactly = 1) {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id,
                    partialContent,
                    userMessage.id,
                    testModel,
                    testSettings,
                    agentRoleId = testRoleId,
                    reasoningItems = null,
                    completion = expectedFailure
                )
            }
            // The message is written once with its content; nothing finalizes it afterwards.
            coVerify(exactly = 0) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
            assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
            coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
            verify(exactly = 0) {
                toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
            }
        }
}
