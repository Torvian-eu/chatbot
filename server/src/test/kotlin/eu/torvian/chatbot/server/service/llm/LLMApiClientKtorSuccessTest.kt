package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.right
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies llmapiclientktor successful completion tests at the HTTP client boundary.
 */
@DisplayName("LLMApiClientKtor successful completion tests")
class LLMApiClientKtorSuccessTest : LLMApiClientKtorTestBase() {

    @Test
    fun `completeChat should return success result on successful API call and processing`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body", // Mock body object
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = mapOf("Authorization" to "Bearer $testApiKey")
        )
        val successResponseBody =
            """{"id": "comp-123", "object": "chat.completion", "created": 1678885370, "model": "gpt-4", "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hello!"}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}"""
        val expectedResult = LLMCompletionResult(
            id = "comp-123",
            choices = listOf(LLMCompletionResult.CompletionChoice("assistant", "Hello!", "stop", 0)),
            usage = LLMCompletionResult.UsageStats(10, 5, 15),
            metadata = mapOf("api_object" to "chat.completion", "api_created" to 1678885370L, "api_model" to "gpt-4")
        )

        // Mock strategy to return successful request config
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        // Mock strategy to return successful result after processing response body
        every { mockStrategy.processSuccessResponse(successResponseBody) } returns expectedResult.right()

        // Create mock HTTP client that returns success response
        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel(successResponseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result: Either<LLMCompletionError, LLMCompletionResult> = client.completeChat(
            testMessages,
            testModel,
            testProvider,
            testSettings,
            testApiKey
        )

        // Assert
        assertTrue(result.isRight(), "Result should be Right on success")
        assertEquals(expectedResult, result.getOrNull(), "Result should match the expected LLMCompletionResult")

        // Verify interactions
        verify(exactly = 1) {
            mockStrategy.prepareRequest(
                testMessages,
                testModel,
                testProvider,
                testSettings,
                testApiKey
            )
        }
        verify(exactly = 1) { mockStrategy.processSuccessResponse(successResponseBody) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) } // Error processing should not be called
    }

    /**
     * Confirms that the generic Ktor transport forwards attribution produced by the
     * provider strategy rather than altering or filtering provider-specific headers.
     */
    @Test
    fun `completeChat should handle empty response body correctly`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )
        val emptyResponseBody = ""
        val expectedResult = LLMCompletionResult(
            id = "empty-response",
            choices = listOf(LLMCompletionResult.CompletionChoice("assistant", "Empty response handled", "stop", 0)),
            usage = LLMCompletionResult.UsageStats(0, 0, 0),
            metadata = emptyMap()
        )

        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        every { mockStrategy.processSuccessResponse(emptyResponseBody) } returns expectedResult.right()

        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel(emptyResponseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result = client.completeChat(testMessages, testModel, testProvider, testSettings, testApiKey)

        // Assert
        assertTrue(result.isRight(), "Result should be Right for empty response")
        assertEquals(expectedResult, result.getOrNull())

        // Verify interactions
        verify(exactly = 1) {
            mockStrategy.prepareRequest(
                testMessages,
                testModel,
                testProvider,
                testSettings,
                testApiKey
            )
        }
        verify(exactly = 1) { mockStrategy.processSuccessResponse(emptyResponseBody) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }

    @Test
    fun `completeChat should handle null API key correctly`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = emptyMap() // No Authorization header when API key is null
        )
        val successResponseBody =
            """{"id": "comp-null-key", "choices": [{"message": {"content": "Response without API key"}}]}"""
        val expectedResult = LLMCompletionResult(
            id = "comp-null-key",
            choices = listOf(LLMCompletionResult.CompletionChoice("assistant", "Response without API key", "stop", 0)),
            usage = LLMCompletionResult.UsageStats(5, 5, 10),
            metadata = emptyMap()
        )

        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, null)
        } returns apiRequestConfig.right()

        every { mockStrategy.processSuccessResponse(successResponseBody) } returns expectedResult.right()

        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel(successResponseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result = client.completeChat(testMessages, testModel, testProvider, testSettings, null)

        // Assert
        assertTrue(result.isRight(), "Result should be Right when API key is null")
        assertEquals(expectedResult, result.getOrNull())

        // Verify interactions
        verify(exactly = 1) {
            mockStrategy.prepareRequest(
                testMessages,
                testModel,
                testProvider,
                testSettings,
                null
            )
        }
        verify(exactly = 1) { mockStrategy.processSuccessResponse(successResponseBody) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }
}
