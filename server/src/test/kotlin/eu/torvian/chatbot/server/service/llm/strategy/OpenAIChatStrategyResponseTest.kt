package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

        assertEquals(10, completionResult.usage?.inputTokens)
        assertEquals(5, completionResult.usage?.outputTokens)
        assertEquals(15, completionResult.usage?.totalTokens)

        assertEquals("chat.completion", completionResult.metadata["api_object"])
        assertEquals(1677652288L, completionResult.metadata["api_created"])
        assertEquals("gpt-4o", completionResult.metadata["api_model"])
    }

    @Test
    @DisplayName("processSuccessResponse should decode a response whose usage is absent")
    fun processSuccessResponse_missingUsage_reportsNoUsage() {
        val responseBody = """
            {
              "id": "chatcmpl-no-usage",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        assertTrue(result.isRight(), "A missing usage must not fail the response: ${result.leftOrNull()}")
        assertNull(assertNotNull(result.getOrNull()).usage, "No reported usage must stay absent")
    }

    @Test
    @DisplayName("processSuccessResponse should derive the total of a partially reported usage")
    fun processSuccessResponse_partialUsage_derivesTotal() {
        val responseBody = """
            {
              "id": "chatcmpl-partial-usage",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10 }
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        val usage = assertNotNull(assertNotNull(result.getOrNull()).usage)
        assertEquals(10, usage.inputTokens)
        assertEquals(0, usage.outputTokens)
        assertEquals(10, usage.totalTokens, "A missing total falls back to input + output")
    }

    @Test
    @DisplayName("processSuccessResponse should report no usage when the usage object carries no counter")
    fun processSuccessResponse_emptyUsage_reportsNoUsage() {
        val responseBody = """
            {
              "id": "chatcmpl-empty-usage",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": {}
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        assertNull(
            assertNotNull(result.getOrNull()).usage,
            "An empty usage object must not become a zero-filled value"
        )
    }

    @Test
    @DisplayName("processSuccessResponse should store a reported cost exactly as the provider stated it")
    fun processSuccessResponse_reportedCost_isStoredAsStated() {
        val responseBody = """
            {
              "id": "chatcmpl-cost",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15,
                "cost": 0.0002466
              }
            }
        """.trimIndent()

        val usage = assertNotNull(assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage)

        assertEquals(0.0002466, usage.cost, "The charge must be recorded without rounding or rescaling")
        assertEquals(10, usage.inputTokens, "The counters are unaffected by the charge")
    }

    @Test
    @DisplayName("processSuccessResponse should report no cost when the provider states none")
    fun processSuccessResponse_withoutCost_reportsNoCost() {
        val responseBody = """
            {
              "id": "chatcmpl-no-cost",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }
        """.trimIndent()

        val usage = assertNotNull(assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage)

        assertNull(usage.cost, "A charge the provider never stated must stay absent")
        assertEquals(15, usage.totalTokens)
    }

    @Test
    @DisplayName("processSuccessResponse should keep a usage object that reports only a cost")
    fun processSuccessResponse_costOnlyUsage_isRetained() {
        val responseBody = """
            {
              "id": "chatcmpl-cost-only",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "cost": 0.95 }
            }
        """.trimIndent()

        val usage = assertNotNull(
            assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage,
            "A reported charge is usage even without a counter"
        )

        assertEquals(0.95, usage.cost)
        assertEquals(0, usage.totalTokens)
    }

    @Test
    @DisplayName("processSuccessResponse should keep a reported zero cost")
    fun processSuccessResponse_zeroCost_isReportedData() {
        val responseBody = """
            {
              "id": "chatcmpl-free",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/free-model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "cost": 0 }
            }
        """.trimIndent()

        val usage = assertNotNull(assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage)

        assertEquals(0.0, usage.cost, "A reported zero is real data, not an absent value")
    }

    @Test
    @DisplayName("processSuccessResponse should accept a numeric-string cost")
    fun processSuccessResponse_numericStringCost_isAccepted() {
        val responseBody = """
            {
              "id": "chatcmpl-string-cost",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "cost": "0.000095" }
            }
        """.trimIndent()

        val usage = assertNotNull(assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage)

        assertEquals(0.000095, usage.cost)
    }

    @Test
    @DisplayName("processSuccessResponse should treat an unusable cost as no cost")
    fun processSuccessResponse_unusableCost_reportsNoCost() {
        // A provider that changes the shape of its extension, and a nonsensical value, must not fail the response.
        val unusableCosts = listOf("{\"amount\": 1}", "[0.5]", "null", "-0.5", "\"not-a-number\"")

        unusableCosts.forEach { rawCost ->
            val responseBody = """
                {
                  "id": "chatcmpl-bad-cost",
                  "object": "chat.completion",
                  "created": 1677652288,
                  "model": "some/model",
                  "choices": [
                    {
                      "index": 0,
                      "message": { "role": "assistant", "content": "Answer" },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "cost": $rawCost }
                }
            """.trimIndent()

            val result = strategy.processSuccessResponse(responseBody)

            assertTrue(result.isRight(), "An unusable cost must not fail the response: $rawCost")
            val usage = assertNotNull(assertNotNull(result.getOrNull()).usage)
            assertNull(usage.cost, "An unusable cost must read as no cost: $rawCost")
            assertEquals(15, usage.totalTokens, "The counters still decode: $rawCost")
        }
    }

    @Test
    @DisplayName("processSuccessResponse should reject an implausibly large cost")
    fun processSuccessResponse_implausibleCost_reportsNoCost() {
        val responseBody = """
            {
              "id": "chatcmpl-huge-cost",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "cost": 1.0E12 }
            }
        """.trimIndent()

        val usage = assertNotNull(assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage)

        assertNull(usage.cost, "A value no single generation can cost is not a charge to record")
    }

    @Test
    @DisplayName("processSuccessResponse should reject a cost at the accepted ceiling and keep the one below it")
    fun processSuccessResponse_costAtCeiling_reportsNoCost() {
        // The ceiling is the first rejected amount, so the largest recordable charge sits just below it.
        val ceiling = 1_000_000.0
        val belowCeiling = 999_999.99

        assertNull(
            costOfResponseReporting(ceiling),
            "The ceiling itself is not a charge to record"
        )
        assertEquals(
            belowCeiling,
            costOfResponseReporting(belowCeiling),
            "The last amount below the ceiling is still a charge"
        )
    }

    /**
     * Maps a response that reports exactly the given charge.
     *
     * @param cost Charge to place in the `usage` object of the response.
     * @return The charge the strategy recorded, or `null` when it recorded none.
     */
    private fun costOfResponseReporting(cost: Double): Double? {
        val responseBody = """
            {
              "id": "chatcmpl-ceiling-cost",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "cost": $cost }
            }
        """.trimIndent()

        val completion = assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull())
        return assertNotNull(completion.usage).cost
    }

    @Test
    @DisplayName("processSuccessResponse should keep a tiny positive cost exactly as stated")
    fun processSuccessResponse_tinyCost_isNotRescaled() {
        // A positive charge far below a cent must be stored as-is, so the reader can still tell a charged request from a free one.
        val responseBody = """
            {
              "id": "chatcmpl-tiny-cost",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "some/model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15, "cost": 1.0E-10 }
            }
        """.trimIndent()

        val usage = assertNotNull(assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull()).usage)

        assertEquals(1.0E-10, usage.cost)
    }

    @Test
    @DisplayName("processSuccessResponse should report a length finish reason as a failure that keeps the content")
    fun processSuccessResponse_lengthFinishReason_carriesProviderFailure() {
        // Given - a generation the provider cut at the model's output limit, with the partial answer still present.
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
                    "content": "Partial answer"
                  },
                  "finish_reason": "length"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }
        """.trimIndent()

        // When
        val result = strategy.processSuccessResponse(responseBody)

        // Then - the ending travels with the mapped output, so the partial text survives the failure.
        val completionResult = assertNotNull(result.getOrNull())
        assertEquals("Partial answer", completionResult.choices.first().content)
        val providerFailure = assertNotNull(completionResult.providerFailure)
        assertEquals("length", providerFailure.providerCode)
    }

    @Test
    @DisplayName("processSuccessResponse should report a content_filter finish reason as a failure")
    fun processSuccessResponse_contentFilterFinishReason_carriesProviderFailure() {
        val responseBody = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "Refused by policy" },
                  "finish_reason": "content_filter"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        val completionResult = assertNotNull(result.getOrNull())
        val providerFailure = assertNotNull(completionResult.providerFailure)
        assertEquals("content_filter", providerFailure.providerCode)
    }

    @Test
    @DisplayName("processSuccessResponse should not report an ending for a stop finish reason")
    fun processSuccessResponse_stopFinishReason_hasNoProviderFailure() {
        val responseBody = """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "created": 1677652288,
              "model": "gpt-4o",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "A complete answer" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        val completionResult = assertNotNull(result.getOrNull())
        assertEquals("A complete answer", completionResult.choices.first().content)
        assertNull(completionResult.providerFailure, "A normally finished generation declares no ending")
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
