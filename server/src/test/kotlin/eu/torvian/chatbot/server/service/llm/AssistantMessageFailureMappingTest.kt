package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.server.data.dao.AssistantMessageCompletionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies [toAssistantMessageCompletionState] and the truncation/unexpected/stream-interrupted factories:
 * every technical failure maps to the expected machine-readable code and a bounded user-facing reason that never
 * leaks provider payloads or exception text.
 */
class AssistantMessageFailureMappingTest {

    @Test
    fun `classifies every LLM completion error type into the expected code`() {
        val expectations = listOf(
            LLMCompletionError.AuthenticationError() to AssistantMessageErrorCode.AUTHENTICATION_FAILED,
            LLMCompletionError.NetworkError("connection reset", RuntimeException("connect timed out")) to
                AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            LLMCompletionError.ApiError(401, "invalid api key provided by the provider", """{"error":"bad key"}""") to
                AssistantMessageErrorCode.AUTHENTICATION_FAILED,
            LLMCompletionError.ApiError(403, "forbidden for this organization", null) to
                AssistantMessageErrorCode.AUTHENTICATION_FAILED,
            LLMCompletionError.ApiError(429, "rate limit reached", null) to
                AssistantMessageErrorCode.RATE_LIMITED,
            LLMCompletionError.ApiError(400, "model does not exist", null) to
                AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
            LLMCompletionError.ApiError(404, "not found", null) to
                AssistantMessageErrorCode.PROVIDER_REQUEST_REJECTED,
            LLMCompletionError.ApiError(500, "internal server error", null) to
                AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            LLMCompletionError.ApiError(503, "overloaded", null) to
                AssistantMessageErrorCode.PROVIDER_UNAVAILABLE,
            LLMCompletionError.InvalidResponseError("success response without choices") to
                AssistantMessageErrorCode.INVALID_PROVIDER_RESPONSE,
            LLMCompletionError.ConfigurationError("ResponsesStrategy requires ResponsesModelSettings") to
                AssistantMessageErrorCode.CONFIGURATION_ERROR,
            LLMCompletionError.OtherError("unexpected") to AssistantMessageErrorCode.UNEXPECTED_ERROR
        )

        expectations.forEach { (error, expectedCode) ->
            val state = error.toAssistantMessageCompletionState()

            assertEquals(expectedCode, state.errorCode, "Unexpected code for $error")
            assertFalse(state.isComplete, "A failure must not be marked completed ($error)")
            assertEquals(AssistantMessageIncompleteCause.FAILED, state.incompleteCause)
            val message = assertNotNull(state.errorMessage, "A failure must carry a reason ($error)")
            assertTrue(message.isNotBlank(), "A failure reason must not be blank ($error)")
        }
    }

    @Test
    fun `never persists provider supplied text or exception messages`() {
        val providerText = "OpenAI Responses API returned error 500: upstream leaked detail"
        val errorBody = """{"error":{"message":"leaked provider body"}}"""
        val exceptionMessage = "socket closed by peer at 10.0.0.7"

        val apiErrorState = LLMCompletionError.ApiError(500, providerText, errorBody)
            .toAssistantMessageCompletionState()
        val networkErrorState = LLMCompletionError.NetworkError(providerText, RuntimeException(exceptionMessage))
            .toAssistantMessageCompletionState()
        val invalidResponseState = LLMCompletionError.InvalidResponseError(providerText)
            .toAssistantMessageCompletionState()
        val otherErrorState = LLMCompletionError.OtherError(providerText, RuntimeException(exceptionMessage))
            .toAssistantMessageCompletionState()

        val persistedMessages = listOf(apiErrorState, networkErrorState, invalidResponseState, otherErrorState)
            .map { assertNotNull(it.errorMessage) }

        persistedMessages.forEach { message ->
            assertFalse(message.contains(providerText), "Provider text leaked into '$message'")
            assertFalse(message.contains("leaked provider body"), "Provider body leaked into '$message'")
            assertFalse(message.contains(exceptionMessage), "Exception text leaked into '$message'")
        }
        // Only the numeric status may appear, so operators can still classify the failure.
        assertTrue(persistedMessages.first().contains("HTTP 500"))
    }

    @Test
    fun `bounds the persisted reason and marks the truncation`() {
        val longConfigurationMessage = "x".repeat(AssistantMessageCompletionState.MAX_ERROR_MESSAGE_CHARS * 2)

        val state = LLMCompletionError.ConfigurationError(longConfigurationMessage)
            .toAssistantMessageCompletionState()
        val message = assertNotNull(state.errorMessage)

        assertEquals(AssistantMessageCompletionState.MAX_ERROR_MESSAGE_CHARS, message.length)
        assertTrue(
            message.endsWith(AssistantMessageCompletionState.ERROR_MESSAGE_TRUNCATION_MARKER),
            "A shortened reason must be marked: '$message'"
        )
        assertEquals(AssistantMessageErrorCode.CONFIGURATION_ERROR, state.errorCode)
    }

    @Test
    fun `output limit state names the limit and its code`() {
        val state = outputLimitExceededCompletionState(64_000)

        assertEquals(AssistantMessageErrorCode.OUTPUT_LIMIT_EXCEEDED, state.errorCode)
        assertEquals(AssistantMessageIncompleteCause.FAILED, state.incompleteCause)
        assertFalse(state.isComplete)
        assertEquals(
            "The response was stopped because it exceeded the 64,000-character limit.",
            state.errorMessage
        )
    }

    @Test
    fun `tool call limit states name their limit and code`() {
        val iterationLimit = toolCallIterationLimitExceededCompletionState(200)
        val perStepLimit = toolCallsPerStepLimitExceededCompletionState(40)
        val argumentLimit = toolCallArgumentLimitExceededCompletionState(300_000)

        assertEquals(AssistantMessageErrorCode.TOOL_CALL_ITERATION_LIMIT_EXCEEDED, iterationLimit.errorCode)
        assertEquals(AssistantMessageErrorCode.TOOL_CALLS_PER_STEP_LIMIT_EXCEEDED, perStepLimit.errorCode)
        assertEquals(AssistantMessageErrorCode.TOOL_CALL_ARGUMENT_LIMIT_EXCEEDED, argumentLimit.errorCode)
        assertEquals(
            "The turn was stopped because the assistant reached the limit of 200 tool-calling steps. " +
                "The tool calls of the last step were still executed.",
            iterationLimit.errorMessage
        )
        assertEquals(
            "The turn was stopped because the assistant asked for more than 40 tool calls in a single step. " +
                "None of the requested tool calls were executed.",
            perStepLimit.errorMessage
        )
        assertEquals(
            "The turn was stopped because a tool call argument exceeded the 300,000-character limit. " +
                "That tool call was not executed; the other tool calls of the step were still executed.",
            argumentLimit.errorMessage
        )
        listOf(iterationLimit, perStepLimit, argumentLimit).forEach { state ->
            assertFalse(state.isComplete, "A reached tool-call limit must not be marked completed")
            assertEquals(AssistantMessageIncompleteCause.FAILED, state.incompleteCause)
            // Every reason is server-authored and fits the persisted budget without truncation.
            val message = assertNotNull(state.errorMessage)
            assertTrue(message.isNotBlank())
            assertTrue(message.length <= AssistantMessageCompletionState.MAX_ERROR_MESSAGE_CHARS)
        }
    }

    @Test
    fun `unexpected failure and stream interruption have distinct codes and generic reasons`() {
        val unexpected = unexpectedFailureCompletionState()
        val interrupted = streamInterruptedCompletionState()

        assertEquals(AssistantMessageErrorCode.UNEXPECTED_ERROR, unexpected.errorCode)
        assertEquals(AssistantMessageErrorCode.STREAM_INTERRUPTED, interrupted.errorCode)
        assertEquals(AssistantMessageIncompleteCause.FAILED, unexpected.incompleteCause)
        assertEquals(AssistantMessageIncompleteCause.FAILED, interrupted.incompleteCause)
        assertTrue(assertNotNull(unexpected.errorMessage).isNotBlank())
        assertTrue(assertNotNull(interrupted.errorMessage).isNotBlank())
    }

    @Test
    fun `interrupted by user carries no reason or code`() {
        val state = AssistantMessageCompletionState.InterruptedByUser

        assertFalse(state.isComplete)
        assertEquals(AssistantMessageIncompleteCause.INTERRUPTED_BY_USER, state.incompleteCause)
        assertEquals(null, state.errorCode)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `completed factory records nothing but the flag`() {
        val state = AssistantMessageCompletionState.Completed

        assertTrue(state.isComplete)
        assertEquals(null, state.incompleteCause)
        assertEquals(null, state.errorCode)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `in flight factory is not completed without a terminal cause`() {
        val state = AssistantMessageCompletionState.InFlight

        assertFalse(state.isComplete)
        assertEquals(null, state.incompleteCause)
        assertEquals(null, state.errorCode)
        assertEquals(null, state.errorMessage)
    }
}
