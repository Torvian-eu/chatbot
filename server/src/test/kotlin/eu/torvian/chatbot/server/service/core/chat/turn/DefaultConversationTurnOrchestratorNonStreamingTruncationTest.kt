package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.outputLimitExceededCompletionState
import eu.torvian.chatbot.server.service.llm.strategy.OllamaChatStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Covers a truncated non-streaming response and the tool calls it must never persist or execute. */
class DefaultConversationTurnOrchestratorNonStreamingTruncationTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies that the persisted content has no in-content notice and is flagged as a length-limit failure, that
     * the turn ends, and that the tool calls parsed from the truncated response are neither persisted nor executed.
     */
    @Test
    fun `processNonStreamingTurn flags a truncated response and never persists or executes its tool calls`() = runTest {
        val toolDefinition = LocalMCPToolDefinition(
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
        val userMessage = ChatMessage.UserMessage(
            id = 611L,
            sessionId = testSession.id,
            content = "Too long",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val oversizedContent = "c".repeat(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS + 3)
        val truncatedContent = oversizedContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        val expectedFailure =
            outputLimitExceededCompletionState(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        val truncatedAssistantMessage = ChatMessage.AssistantMessage(
            id = 612L,
            sessionId = testSession.id,
            content = truncatedContent,
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
        val truncatedCompletion = LLMCompletionResult(
            id = "completion-truncated",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = oversizedContent,
                    finishReason = "tool_calls",
                    index = 0,
                    toolCalls = listOf(
                        LLMCompletionResult.CompletionChoice.ToolCallRequest(
                            name = toolDefinition.name,
                            arguments = "{\"query\":\"docs\"}",
                            toolCallId = "call-truncated"
                        )
                    )
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            metadata = emptyMap()
        )

        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Too long", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns truncatedCompletion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                truncatedContent,
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(truncatedAssistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key", listOf(toolDefinition)),
                content = "Too long",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        assertEquals(3, events.size)
        assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
        val savedTruncated = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(truncatedContent, savedTruncated.assistantMessage.content)
        assertEquals(AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED, savedTruncated.assistantMessage.errorCode)
        assertFalse(truncatedContent.contains("[Output truncated"))
        assertEquals(ConversationTurnEvent.TurnCompleted, events[2])
        // The truncated response terminates the tool loop, so nothing is persisted and nothing executes.
        assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
        coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
        verify(exactly = 0) {
            toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
        }
    }

    /**
     * Verifies the non-streaming Ollama path end to end: the dialect reports the server's `length` terminal reason
     * as a declared ending on the result, so the message keeps the partial answer, is written once with the mapped
     * failure state, and never reaches the tool layer even though the response asked for a call.
     */
    @Test
    fun `processNonStreamingTurn persists an Ollama length truncation with its partial content and runs no tool`() =
        runTest {
            val toolDefinition = LocalMCPToolDefinition(
                id = 12L,
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
                id = 631L,
                sessionId = testSession.id,
                content = "Ollama truncated",
                createdAt = baseInstant,
                updatedAt = baseInstant,
                parentMessageId = null,
                childrenMessageIds = emptyList()
            )
            val declaredEnding = assertNotNull(
                OllamaChatStrategy(Json { ignoreUnknownKeys = true; isLenient = true })
                    .processSuccessResponse(
                        """
                            {
                                "model": "llama3.2",
                                "created_at": "2023-12-07T09:32:18Z",
                                "message": {
                                    "role": "assistant",
                                    "content": "Partial answer",
                                    "tool_calls": [
                                        { "function": { "name": "search", "arguments": { "query": "docs" } } }
                                    ]
                                },
                                "done": true,
                                "done_reason": "length",
                                "prompt_eval_count": 26,
                                "eval_count": 15
                            }
                        """.trimIndent()
                    )
                    .getOrNull()
            )
            val expectedFailure = AssistantMessageCompletionState.failed(
                code = AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
                message = "The provider stopped the response because the model reached its output limit."
            )
            val partialAssistantMessage = ChatMessage.AssistantMessage(
                id = 632L,
                sessionId = testSession.id,
                content = "Partial answer",
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
                conversationTurnPersistence.saveUserMessage(testSession.id, "Ollama truncated", null, any())
            } returns PersistedUserMessage(userMessage, null)
            coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
            coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns
                    declaredEnding.right()
            coEvery {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id,
                    "Partial answer",
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
                    content = "Ollama truncated",
                    parentMessageId = null,
                    fileReferences = emptyList(),
                    toolApprovalFlow = emptyFlow(),
                    operatorToolResultFlow = emptyFlow(),
                    turnControlSignal = TurnControlSignal()
                )
            ).toList()

            // One write carrying the partial content plus the mapped state, one transient frame, one terminal frame.
            coVerify(exactly = 1) {
                conversationTurnPersistence.saveAssistantMessage(
                    testSession.id,
                    "Partial answer",
                    userMessage.id,
                    testModel,
                    testSettings,
                    agentRoleId = testRoleId,
                    reasoningItems = null,
                    completion = expectedFailure
                )
            }
            coVerify(exactly = 0) { conversationTurnPersistence.updateAssistantMessageContent(any(), any(), any()) }
            assertEquals(4, events.size)
            assertIs<ConversationTurnEvent.UserMessageSaved>(events[0])
            val savedPartialStep = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
            assertEquals("Partial answer", savedPartialStep.assistantMessage.content)
            assertEquals(
                AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
                savedPartialStep.assistantMessage.errorCode
            )
            val errorFrame = assertIs<ConversationTurnEvent.ExternalServiceError>(events[2])
            assertEquals(
                "length",
                assertIs<LLMCompletionError.ProviderFailureError>(errorFrame.llmError).providerCode
            )
            assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
            // The call the model asked for before the cut reaches neither persistence nor execution.
            assertTrue(events.none { it is ConversationTurnEvent.ToolCallsReceived })
            coVerify(exactly = 0) { conversationTurnPersistence.persistPendingToolCalls(any(), any(), any()) }
            verify(exactly = 0) {
                toolCallOrchestrator.executeAndUpdateToolCalls(any(), any(), any(), any(), any(), any())
            }
        }

    /**
     * Verifies that a provider-declared ending outranks the server's character cap while the cap still bounds the
     * persisted text: the two limits keep their distinct codes instead of the cap deciding by default.
     */
    @Test
    fun `processNonStreamingTurn prefers a declared ending over the character cap and still cuts the content`() = runTest {
        val userMessage = ChatMessage.UserMessage(
            id = 633L,
            sessionId = testSession.id,
            content = "Long and truncated",
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
        val oversizedContent = "e".repeat(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS + 4)
        val cappedContent = oversizedContent.take(ConversationTurnLimits.MAX_ASSISTANT_MESSAGE_CHARS)
        val declaredEnding = LLMCompletionError.ProviderFailureError(
            providerCode = "max_output_tokens",
            message = "The provider ended the response without completing it."
        )
        val expectedFailure = AssistantMessageCompletionState.failed(
            code = AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED,
            message = "The provider stopped the response because the model reached its output limit."
        )
        val cappedAssistantMessage = ChatMessage.AssistantMessage(
            id = 634L,
            sessionId = testSession.id,
            content = cappedContent,
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
        val completion = LLMCompletionResult(
            id = "completion-capped-and-truncated",
            choices = listOf(
                LLMCompletionResult.CompletionChoice(
                    role = "assistant",
                    content = oversizedContent,
                    finishReason = "stop",
                    index = 0
                )
            ),
            usage = LLMCompletionResult.UsageStats(1, 1, 2),
            providerFailure = declaredEnding
        )
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, "Long and truncated", null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                cappedContent,
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        } returns PersistedAssistantMessage(cappedAssistantMessage, userMessage)

        val events = orchestrator.processNonStreamingTurn(
            ConversationTurnRequest(
                userId = 1L,
                session = testSession,
                llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
                content = "Long and truncated",
                parentMessageId = null,
                fileReferences = emptyList(),
                toolApprovalFlow = emptyFlow(),
                operatorToolResultFlow = emptyFlow(),
                turnControlSignal = TurnControlSignal()
            )
        ).toList()

        // The text is still cut at the cap, but the state is the provider's own classification.
        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                cappedContent,
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure
            )
        }
        val savedStep = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1])
        assertEquals(cappedContent, savedStep.assistantMessage.content)
        assertEquals(AssistantMessageErrorCode.PROVIDER_OUTPUT_LIMIT_EXCEEDED, savedStep.assistantMessage.errorCode)
        assertEquals(declaredEnding, assertIs<ConversationTurnEvent.ExternalServiceError>(events[2]).llmError)
        assertEquals(ConversationTurnEvent.TurnCompleted, events[3])
        assertEquals(1, events.count { it == ConversationTurnEvent.TurnCompleted })
    }
}
