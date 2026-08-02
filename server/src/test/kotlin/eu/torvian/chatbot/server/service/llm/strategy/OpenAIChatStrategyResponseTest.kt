package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies openaichatstrategy response tests behavior at its public strategy boundary.
 */
@DisplayName("OpenAIChatStrategy response tests")
class OpenAIChatStrategyResponseTest : OpenAIChatStrategyTestBase() {

    @Test
    @DisplayName("processSuccessResponse should successfully parse valid OpenAI response")
    fun processSuccessResponse_success() {
        // Given
        val responseBody = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "This is a test response."
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15
              }
            }
        """.trimIndent()

        // When
        val result = strategy.processSuccessResponse(responseBody)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val completionResult = result.getOrNull()
        assertNotNull(completionResult, "Expected non-null LLMCompletionResult")

        assertEquals("chatcmpl-123", completionResult.id)
        assertEquals(1, completionResult.choices.size)

        val choice = completionResult.choices.first()
        assertEquals("assistant", choice.role)
        assertEquals("This is a test response.", choice.content)
        assertEquals("stop", choice.finishReason)
        assertEquals(0, choice.index)

        assertEquals(10, completionResult.usage.promptTokens)
        assertEquals(5, completionResult.usage.completionTokens)
        assertEquals(15, completionResult.usage.totalTokens)

        assertEquals("chat.completion", completionResult.metadata["api_object"])
        assertEquals(1677652288L, completionResult.metadata["api_created"])
        assertEquals("gpt-4o", completionResult.metadata["api_model"])
    }

    @Test
    @DisplayName("processSuccessResponse should return InvalidResponseError for invalid JSON")
    fun processSuccessResponse_invalidJson() {
        // Given
        val responseBody =
            """{"id": "chatcmpl-123", "object": "chat.completion", "created": 1677652288, "model": "gpt-4o", "choices": [ {"index": 0, "message": {"role": "assistant", "content": "This is a test response."}, "finish_reason": "stop" } ], "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 """ // Missing closing brace

        // When
        val result = strategy.processSuccessResponse(responseBody)

        // Then
        assertTrue(result.isLeft(), "Expected error result")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.InvalidResponseError>(error, "Expected InvalidResponseError")
        assertTrue(
            error.message.contains("Failed to parse OpenAI success response body"),
            "Error message should indicate parsing failure"
        )
        assertNotNull(error.cause, "Error should contain the underlying exception")
    }

    @Test
    @DisplayName("processSuccessResponse should return InvalidResponseError for unexpected JSON structure")
    fun processSuccessResponse_unexpectedStructure() {
        // Given
        val responseBody = """
            {
              "some_other_field": "value",
              "data": {
                "result": "unexpected"
              }
            }
        """.trimIndent()

        // When
        val result = strategy.processSuccessResponse(responseBody)

        // Then
        assertTrue(result.isLeft(), "Expected error result")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.InvalidResponseError>(error, "Expected InvalidResponseError")
        assertTrue(
            error.message.contains("Failed to parse OpenAI success response body"),
            "Error message should indicate parsing failure"
        )
        assertNotNull(error.cause, "Error should contain the underlying exception")
    }

    // --- processErrorResponse Tests ---
    @Test
    @DisplayName("processErrorResponse should return AuthenticationError for 401 status")
    fun processErrorResponse_401AuthenticationError() {
        // Given
        val statusCode = 401
        val errorBody = """
            {
              "error": {
                "message": "Incorrect API key provided.",
                "type": "invalid_request_error",
                "param": null,
                "code": "invalid_api_key"
              }
            }
        """.trimIndent()

        // When
        val error = strategy.processErrorResponse(statusCode, errorBody)

        // Then
        assertIs<LLMCompletionError.AuthenticationError>(error, "Expected AuthenticationError")
        assertTrue(
            error.message.contains("Incorrect API key provided."),
            "Error message should contain API error detail"
        )
    }

    @Test
    @DisplayName("processErrorResponse should return ApiError for 400 status")
    fun processErrorResponse_400ApiError() {
        // Given
        val statusCode = 400
        val errorBody = """
            {
              "error": {
                "message": "Invalid value for 'messages[0].role': 'system'. Expected one of ['system', 'user', 'assistant', 'tool', 'function'].",
                "type": "invalid_request_error",
                "param": "messages[0].role",
                "code": null
              }
            }
        """.trimIndent()

        // When
        val error = strategy.processErrorResponse(statusCode, errorBody)

        // Then
        assertIs<LLMCompletionError.ApiError>(error, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertEquals(
            true,
            error.message?.contains("Invalid value for 'messages[0].role'"),
            "Error message should contain API error detail"
        )
        assertEquals(errorBody, error.errorBody)
    }

    @Test
    @DisplayName("processErrorResponse should return ApiError for 429 status")
    fun processErrorResponse_429ApiError() {
        // Given
        val statusCode = 429
        val errorBody = """
            {
              "error": {
                "message": "You exceeded your current quota, please check your plan and billing details.",
                "type": "insufficient_quota",
                "param": null,
                "code": "insufficient_quota"
              }
            }
        """.trimIndent()

        // When
        val error = strategy.processErrorResponse(statusCode, errorBody)

        // Then
        assertIs<LLMCompletionError.ApiError>(error, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertEquals(
            true,
            error.message?.contains("You exceeded your current quota"),
            "Error message should contain API error detail"
        )
        assertEquals(errorBody, error.errorBody)
    }

    @Test
    @DisplayName("processErrorResponse should return ApiError for 500 status")
    fun processErrorResponse_500ApiError() {
        // Given
        val statusCode = 500
        val errorBody = """
            {
              "error": {
                "message": "The server had an error while processing your request. Sorry about that!",
                "type": "server_error",
                "param": null,
                "code": null
              }
            }
        """.trimIndent()

        // When
        val error = strategy.processErrorResponse(statusCode, errorBody)

        // Then
        assertIs<LLMCompletionError.ApiError>(error, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertEquals(
            true,
            error.message?.contains("The server had an error"),
            "Error message should contain API error detail"
        )
        assertEquals(errorBody, error.errorBody)
    }

    @Test
    @DisplayName("processErrorResponse should return ApiError with raw body snippet if error body parsing fails")
    fun processErrorResponse_invalidErrorBody() {
        // Given
        val statusCode = 400
        val errorBody = """{"not_an_openai_error": "details", "malformed": """ // Invalid JSON

        // When
        val error = strategy.processErrorResponse(statusCode, errorBody)

        // Then
        assertIs<LLMCompletionError.ApiError>(error, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertEquals(
            true,
            error.message?.contains("OpenAI API returned error $statusCode"),
            "Error message should indicate status code"
        )
        // Message should contain a snippet of the raw body if parsing failed
        assertEquals(
            true,
            error.message?.contains(errorBody.take(200)),
            "Error message should contain snippet of raw body"
        )
        assertEquals(errorBody, error.errorBody)
    }

    // --- processStreamingResponse Tests ---
}
