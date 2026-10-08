package eu.torvian.chatbot.server.service.core.chat.turn

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import eu.torvian.chatbot.server.runtime.TurnControlSignal
import eu.torvian.chatbot.server.service.core.LLMConfig
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedAssistantMessage
import eu.torvian.chatbot.server.service.core.chat.persistence.PersistedUserMessage
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.toAssistantMessageCompletionState
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the measured provider-call duration of a non-streaming assistant step: every row the step writes —
 * a successful answer, the empty row of a failed call, the empty row of a response without choices, and a
 * provider-declared non-completion — carries the duration of the call that produced it.
 */
class DefaultConversationTurnOrchestratorNonStreamingResponseDurationTest : DefaultConversationTurnOrchestratorTestBase() {

    /** Durations passed to the assistant insert of each step, in call order. */
    private val capturedResponseDurations = mutableListOf<Long?>()

    /** Clears the captured durations so an assertion never sees a previous test's insert. */
    @BeforeEach
    fun resetCapturedResponseDurations() {
        capturedResponseDurations.clear()
    }

    /**
     * Stubs the assistant insert so the measured duration is captured, returning the given message.
     *
     * @param assistantMessage Message the insert returns.
     * @param userMessage User message the insert refreshes as parent.
     */
    private fun stubAssistantInsertCapturingDuration(
        assistantMessage: ChatMessage.AssistantMessage,
        userMessage: ChatMessage.UserMessage
    ) {
        coEvery {
            conversationTurnPersistence.saveAssistantMessage(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } answers {
            // The measured duration is the last declared parameter, so it is read positionally from the recorded
            // invocation.
            capturedResponseDurations.add(invocation.args[9] as Long?)
            PersistedAssistantMessage(assistantMessage, userMessage)
        }
    }

    /**
     * Stubs the shared turn start of a single-call non-streaming turn.
     *
     * @param userMessage User message persisted at turn start.
     */
    private fun stubUserMessage(userMessage: ChatMessage.UserMessage) {
        coEvery {
            conversationTurnPersistence.saveUserMessage(testSession.id, userMessage.content, null, any())
        } returns PersistedUserMessage(userMessage, null)
        coEvery { conversationTurnPersistence.loadSessionToolCalls(testSession.id) } returns emptyList()
    }

    /**
     * Verifies the empty failed row of a rejected call carries the measured duration of that call.
     */
    @Test
    fun `processNonStreamingTurn records the duration of a failed provider call on the empty failed row`() = runTest {
        val userMessage = userMessage(801L, "Answer me")
        val failedMessage = assistantMessage(id = 802L, parentMessageId = userMessage.id, content = "")
        stubUserMessage(userMessage)
        stubAssistantInsertCapturingDuration(failedMessage, userMessage)
        val failure = LLMCompletionError.NetworkError("connect timed out", RuntimeException("connect timed out"))
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns failure.left()

        orchestrator.processNonStreamingTurn(nonStreamingRequest("Answer me")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "A failed call must record a duration")
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                sessionId = testSession.id,
                content = "",
                parentMessageId = userMessage.id,
                model = testModel,
                settings = testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = failure.toAssistantMessageCompletionState(),
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies the empty failed row of a response without choices carries the measured duration of that call.
     */
    @Test
    fun `processNonStreamingTurn records the duration of a response without choices`() = runTest {
        val userMessage = userMessage(803L, "Answer me")
        val failedMessage = assistantMessage(id = 804L, parentMessageId = userMessage.id, content = "")
        stubUserMessage(userMessage)
        stubAssistantInsertCapturingDuration(failedMessage, userMessage)
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns
                LLMCompletionResult(choices = emptyList(), usage = null).right()

        orchestrator.processNonStreamingTurn(nonStreamingRequest("Answer me")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "An unparsable response must be measured")
        assertTrue(duration >= 0, "A measured duration is never negative")
        val expectedFailure = LLMCompletionError.InvalidResponseError(
            "LLM API returned success but no completion choices."
        ).toAssistantMessageCompletionState()
        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                sessionId = testSession.id,
                content = "",
                parentMessageId = userMessage.id,
                model = testModel,
                settings = testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                completion = expectedFailure,
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies a completed answer stores the measured duration of its provider call together with its usage.
     */
    @Test
    fun `processNonStreamingTurn records the duration of a completed provider call`() = runTest {
        val reportedUsage = UsageStats(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        val userMessage = userMessage(805L, "Count my tokens")
        val assistant = assistantMessage(id = 806L, parentMessageId = userMessage.id, content = "Answer")
        stubUserMessage(userMessage)
        stubAssistantInsertCapturingDuration(assistant, userMessage)
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns
                completionResult(content = "Answer", usage = reportedUsage).right()

        orchestrator.processNonStreamingTurn(nonStreamingRequest("Count my tokens")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "A completed call must record a duration")
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                sessionId = testSession.id,
                content = "Answer",
                parentMessageId = userMessage.id,
                model = testModel,
                settings = testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = reportedUsage,
                completion = AssistantMessageCompletionState.Completed,
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies a generation the provider declared uncompleted still stores the duration of the attempt, while its
     * usage stays absent.
     */
    @Test
    fun `processNonStreamingTurn records the duration of a provider-declared non-completion`() = runTest {
        val providerFailure = LLMCompletionError.ProviderFailureError(
            providerCode = "length",
            message = "The generation was cut short by the provider."
        )
        val userMessage = userMessage(807L, "Cut me off")
        val assistant = assistantMessage(id = 808L, parentMessageId = userMessage.id, content = "Cut off")
        stubUserMessage(userMessage)
        stubAssistantInsertCapturingDuration(assistant, userMessage)
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } returns
                completionResult(content = "Cut off", usage = UsageStats(10, 5, 15), providerFailure = providerFailure).right()

        orchestrator.processNonStreamingTurn(nonStreamingRequest("Cut me off")).toList()

        val duration = assertNotNull(capturedResponseDurations.single(), "A cut-off attempt must be measured")
        assertTrue(duration >= 0, "A measured duration is never negative")
        coVerify(exactly = 1) {
            conversationTurnPersistence.saveAssistantMessage(
                sessionId = testSession.id,
                content = "Cut off",
                parentMessageId = userMessage.id,
                model = testModel,
                settings = testSettings,
                agentRoleId = testRoleId,
                reasoningItems = null,
                usageStats = null,
                completion = providerFailure.toAssistantMessageCompletionState(),
                responseDurationMs = duration
            )
        }
    }

    /**
     * Verifies the measured window really wraps the provider call and that its unit is milliseconds, by blocking
     * the call for a real, non-virtual pause and asserting a lower bound on the captured value.
     */
    @Test
    fun `processNonStreamingTurn measures the blocked provider call in milliseconds`() = runTest {
        val userMessage = userMessage(809L, "Slow answer")
        val assistant = assistantMessage(id = 810L, parentMessageId = userMessage.id, content = "Slow answer")
        stubUserMessage(userMessage)
        stubAssistantInsertCapturingDuration(assistant, userMessage)
        coEvery { llmApiClient.completeChat(any(), any(), any(), any(), any(), any()) } coAnswers {
            // A blocking pause, deliberately not a `delay` (virtual time would skip it).
            Thread.sleep(BLOCKING_CALL_MILLIS)
            completionResult(content = "Slow answer", usage = null).right()
        }

        orchestrator.processNonStreamingTurn(nonStreamingRequest("Slow answer")).toList()

        val duration = assertNotNull(capturedResponseDurations.single())
        assertTrue(
            duration >= MINIMUM_EXPECTED_MILLIS,
            "A call blocked for $BLOCKING_CALL_MILLIS ms must be measured as at least $MINIMUM_EXPECTED_MILLIS ms, " +
                "but was $duration ms"
        )
    }

    /**
     * Builds a one-choice completion result with the fixture content.
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
        choices = listOf(
            LLMCompletionResult.CompletionChoice(
                role = "assistant",
                content = content,
                finishReason = "stop",
                index = 0
            )
        ),
        usage = usage,
        providerFailure = providerFailure
    )

    /**
     * Builds an assistant-message fixture of the shared session.
     *
     * @param id Message identifier.
     * @param parentMessageId Parent the message replies to.
     * @param content Message content.
     * @return Assistant message belonging to the shared session fixtures.
     */
    private fun assistantMessage(id: Long, parentMessageId: Long, content: String) = ChatMessage.AssistantMessage(
        id = id,
        sessionId = testSession.id,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = parentMessageId,
        childrenMessageIds = emptyList(),
        modelId = testModel.id,
        settingsId = testSettings.id
    )

    /**
     * Builds a user-message fixture of the shared session.
     *
     * @param id Message identifier.
     * @param content Message content.
     * @return User message belonging to the shared session fixtures.
     */
    private fun userMessage(id: Long, content: String) = ChatMessage.UserMessage(
        id = id,
        sessionId = testSession.id,
        content = content,
        createdAt = baseInstant,
        updatedAt = baseInstant,
        parentMessageId = null,
        childrenMessageIds = emptyList()
    )

    /**
     * Builds a single-call non-streaming turn request on the shared fixtures.
     *
     * @param content User content of the turn.
     * @return Turn request wired to the shared test fixtures.
     */
    private fun nonStreamingRequest(content: String) = ConversationTurnRequest(
        userId = 1L,
        session = testSession,
        llmConfig = LLMConfig(testProvider, testModel, testSettings, "api-key"),
        resolvedCompaction = defaultResolvedCompaction,
        content = content,
        parentMessageId = null,
        fileReferences = emptyList(),
        toolApprovalFlow = emptyFlow(),
        operatorToolResultFlow = emptyFlow(),
        turnControlSignal = TurnControlSignal()
    )

    private companion object {
        /** Real pause the blocking-stub test imposes on the provider call. */
        const val BLOCKING_CALL_MILLIS = 25L

        /** Lowest captured value the blocked call must produce, tolerating scheduling overhead. */
        const val MINIMUM_EXPECTED_MILLIS = 20L
    }
}
