package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers which token usage a non-streaming assistant step persists: the provider-reported usage of a completed
 * response, and nothing for a generation the provider itself declared uncompleted.
 */
class DefaultConversationTurnOrchestratorNonStreamingUsageTest : DefaultConversationTurnOrchestratorTestBase() {

    /**
     * Verifies that the usage of a completed response is persisted with the assistant message and delivered
     * with the saved-message event.
     */
    @Test
    fun `processNonStreamingTurn persists the usage of a completed response`() = runTest {
        val reportedUsage = UsageStats(
            inputTokens = 120,
            outputTokens = 30,
            totalTokens = 150,
            reasoningTokens = 12,
            cachedTokens = 8,
            cacheWriteTokens = 4
        )
        val completion = completionResult(content = "Answer", usage = reportedUsage)
        val userMessage = nonStreamingUserMessage(701L, "Count my tokens")
        val assistantMessage = nonStreamingAssistantMessage(
            id = 702L,
            parentMessageId = userMessage.id,
            content = "Answer",
            usageStats = reportedUsage
        )
        stubSingleCallTurn(userMessage, completion, assistantMessage, expectedUsage = reportedUsage)

        val events = orchestrator.processNonStreamingTurn(nonStreamingRequest("Count my tokens")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Answer",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = reportedUsage,
                responseDurationMs = any(),
                completion = any()
            )
        }
        val saved = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1]).assistantMessage
        assertEquals(reportedUsage, saved.usageStats)
    }

    /**
     * Verifies that a response the provider declared uncompleted records no usage, even though the provider
     * reported counters for the partial output.
     */
    @Test
    fun `processNonStreamingTurn records no usage for a provider-declared non-completion`() = runTest {
        val providerFailure = LLMCompletionError.ProviderFailureError(
            providerCode = "length",
            message = "The generation was cut short by the provider."
        )
        val completion = completionResult(
            content = "Cut off",
            usage = UsageStats(10, 5, 15),
            providerFailure = providerFailure
        )
        val userMessage = nonStreamingUserMessage(711L, "Cut me off")
        val assistantMessage = nonStreamingAssistantMessage(
            id = 712L,
            parentMessageId = userMessage.id,
            content = "Cut off"
        )
        stubSingleCallTurn(userMessage, completion, assistantMessage, expectedUsage = null)

        val events = orchestrator.processNonStreamingTurn(nonStreamingRequest("Cut me off")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Cut off",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = null,
                responseDurationMs = any(),
                completion = any()
            )
        }
        val saved = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1]).assistantMessage
        assertNull(saved.usageStats, "A provider-declared non-completion records no usage")
    }

    /**
     * Verifies a response the provider reported without counters records no usage instead of a zero-filled one.
     */
    @Test
    fun `processNonStreamingTurn records no usage when the provider reports none`() = runTest {
        val completion = completionResult(content = "Answer", usage = null)
        val userMessage = nonStreamingUserMessage(721L, "No usage")
        val assistantMessage = nonStreamingAssistantMessage(
            id = 722L,
            parentMessageId = userMessage.id,
            content = "Answer"
        )
        stubSingleCallTurn(userMessage, completion, assistantMessage, expectedUsage = null)

        val events = orchestrator.processNonStreamingTurn(nonStreamingRequest("No usage")).toList()

        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                "Answer",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = null,
                responseDurationMs = any(),
                completion = any()
            )
        }
        val saved = assertIs<ConversationTurnEvent.AssistantMessageSaved>(events[1]).assistantMessage
        assertNull(saved.usageStats)
    }

    /**
     * Builds an assistant-message fixture of the shared session.
     *
     * @param id Message identifier.
     * @param parentMessageId Parent the message replies to.
     * @param content Message content.
     * @param usageStats Usage carried by the message, or `null` when it has none.
     * @return Assistant message belonging to the shared session fixtures.
     */
    private fun nonStreamingAssistantMessage(
        id: Long,
        parentMessageId: Long,
        content: String,
        usageStats: UsageStats? = null
    ) = ChatMessage.AssistantMessage(
        id = id,
        sessionId = testSession.id,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = parentMessageId,
        childrenMessageIds = emptyList(),
        modelId = testModel.id,
        settingsId = testSettings.id,
        usageStats = usageStats
    )

    /**
     * Builds a user-message fixture of the shared session.
     *
     * @param id Message identifier.
     * @param content Message content.
     * @return User message belonging to the shared session fixtures.
     */
    private fun nonStreamingUserMessage(id: Long, content: String) =
        ChatMessage.UserMessage(
            id = id,
            sessionId = testSession.id,
            content = content,
            createdAt = baseInstant,
            updatedAt = baseInstant,
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )

    /**
     * Builds a one-choice completion result.
     *
     * @param content Generated assistant text.
     * @param usage Usage the provider reported, or `null` when it reported none.
     * @param providerFailure Ending the provider declared, or `null` when the response completed.
     * @return Completion result sharing the fixture model metadata.
     */
    private fun completionResult(
        content: String,
        usage: UsageStats?,
        providerFailure: LLMCompletionError.ProviderFailureError? = null
    ) = LLMCompletionResult(
        id = "completion-usage",
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = content,
                finishReason = "stop",
                index = 0
            )
        ),
        usage = usage,
        metadata = emptyMap(),
        providerFailure = providerFailure
    )

    /**
     * Stubs the start of a single-call turn and the assistant insert it performs.
     *
     * @param userMessage User message persisted at turn start.
     * @param completion Completion the LLM client returns.
     * @param assistantMessage Message the insert returns.
     * @param expectedUsage Usage the turn is expected to pass to the insert, or `null` when it passes none.
     */
    private fun stubSingleCallTurn(
        userMessage: ChatMessage.UserMessage,
        completion: LLMCompletionResult,
        assistantMessage: ChatMessage.AssistantMessage,
        expectedUsage: UsageStats?
    ) {
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, userMessage.content, null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns completion.right()
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                testSession.id,
                completion.choices.first().content ?: "",
                userMessage.id,
                testModel,
                testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = expectedUsage,
                completion = any(),
                responseDurationMs = any()
            )
        } returns PersistedAssistantMessage(assistantMessage, userMessage)
    }

    /**
     * Builds a turn request on the shared fixtures for a single-call (non-streaming) turn.
     *
     * @param content User content of the turn.
     * @return Turn request wired to the shared test fixtures.
     */
    private fun nonStreamingRequest(content: String) = ConversationTurnRequest(
        userId = 1L,
        session = testSession,
        llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
        content = content,
        parentMessageId = null,
        fileReferences = emptyList(),
        toolApprovalFlow = emptyFlow(),
        operatorToolResultFlow = emptyFlow(),
        turnControlSignal = TurnControlSignal()
    )
}
