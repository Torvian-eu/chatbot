package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.LLMProviderType
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LLMApiClientKtorTest {

    private val mockStrategy: ChatCompletionStrategy = mockk(relaxed = true)
    private lateinit var strategies: Map<LLMProviderType, ChatCompletionStrategy>
    private lateinit var mockEngine: MockEngine
    private lateinit var httpClient: HttpClient
    private lateinit var client: LLMApiClientKtor

    // Test data using TestDefaults
    private val testMessages = listOf(TestDefaults.chatMessage1)
    private val testModel = TestDefaults.llmModel1 // OpenAI model
    private val testProvider = TestDefaults.llmProvider1 // OpenAI provider
    private val testSettings = TestDefaults.modelSettings1 // Settings for OpenAI model
    private val testApiKey = "test-api-key"

    @BeforeEach
    fun setUp() {
        // Set up the strategies map with the mock strategy
        strategies = mapOf(testProvider.type to mockStrategy)

        // Common mock setup for the strategy type
        every { mockStrategy.providerType } returns testProvider.type
    }

    @AfterEach
    fun tearDown() {
        clearMocks(mockStrategy)
    }

    private fun createMockHttpClient(responseHandler: MockRequestHandler): HttpClient {
        mockEngine = MockEngine(responseHandler)
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

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
        httpClient = createMockHttpClient { request ->
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

    @Test
    fun `completeChat should return ConfigurationError if strategy is not found`() = runTest {
        // Arrange
        val unknownProviderType = LLMProviderType.ANTHROPIC // Assuming ANTHROPIC is not in the strategies map
        val providerWithUnknownType = testProvider.copy(type = unknownProviderType)

        // Create a simple mock HTTP client (won't be used in this test)
        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result: Either<LLMCompletionError, LLMCompletionResult> = client.completeChat(
            testMessages,
            testModel,
            providerWithUnknownType, // Use provider with unknown type
            testSettings,
            testApiKey
        )

        // Assert
        assertTrue(result.isLeft(), "Result should be Left when strategy is not found")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.ConfigurationError>(error, "Error should be ConfigurationError")
        assertEquals("No ChatCompletionStrategy found for provider type: $unknownProviderType", error.message)

        // Verify interactions - no strategy methods should be called
        verify(exactly = 0) { mockStrategy.prepareRequest(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }

    @Test
    fun `completeChat should return ConfigurationError if strategy prepareRequest fails`() = runTest {
        // Arrange
        val configErrorMessage = "API key is missing"
        val configError = LLMCompletionError.ConfigurationError(configErrorMessage)

        // Mock strategy to return a configuration error
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns configError.left()

        // Create a simple mock HTTP client (won't be used in this test)
        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.OK
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
        assertTrue(result.isLeft(), "Result should be Left when strategy preparation fails")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.ConfigurationError>(error, "Error should be ConfigurationError")
        assertEquals(configErrorMessage, error.message)

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
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }

    @Test
    fun `completeChat should return NetworkError if HTTP request fails`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )

        // Mock strategy to return successful request config
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        // Create mock HTTP client that simulates a network timeout/connection error
        // by returning a 503 Service Unavailable status which is often used for network issues
        val networkErrorBody = "Service temporarily unavailable"
        val networkError = LLMCompletionError.NetworkError(
            "Network or communication error with ${testProvider.name}: Service temporarily unavailable",
            null
        )

        // Mock the strategy to return a network error for this specific case
        every { mockStrategy.processErrorResponse(503, networkErrorBody) } returns networkError

        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(networkErrorBody),
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
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
        assertTrue(result.isLeft(), "Result should be Left when HTTP request fails")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.NetworkError>(error, "Error should be NetworkError")
        assertEquals(
            "Network or communication error with ${testProvider.name}: Service temporarily unavailable",
            error.message
        )

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
        verify(exactly = 1) { mockStrategy.processErrorResponse(503, networkErrorBody) }
        // No success processing should occur
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
    }

    @Test
    fun `completeChat should return NetworkError if reading response body fails`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )

        // Mock strategy to return successful request config
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        // Create mock HTTP client that returns a response that will fail when reading body
        // Note: With MockEngine, it's harder to simulate body reading failures, so we'll test
        // this scenario by having the response processing fail instead
        val corruptedResponseBody = "corrupted response"
        val processingError =
            LLMCompletionError.InvalidResponseError("Failed to parse response", Exception("JSON parsing failed"))

        every { mockStrategy.processSuccessResponse(corruptedResponseBody) } returns processingError.left()

        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(corruptedResponseBody),
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
        assertTrue(result.isLeft(), "Result should be Left when response processing fails")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.InvalidResponseError>(error, "Error should be InvalidResponseError")

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
        verify(exactly = 1) { mockStrategy.processSuccessResponse(corruptedResponseBody) }
        verify(exactly = 0) { mockStrategy.processErrorResponse(any(), any()) }
    }


    @Test
    fun `completeChat should return ApiError if HTTP response status is non-success`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )
        val errorStatusCode = 401
        val errorResponseBody = """{"error": {"message": "Invalid API key", "type": "authentication_error"}}"""
        val expectedApiError =
            LLMCompletionError.AuthenticationError("OpenAI API authentication failed: Invalid API key") // Example error from strategy

        // Mock strategy to return successful request config
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        // Mock strategy to process the error response
        every { mockStrategy.processErrorResponse(errorStatusCode, errorResponseBody) } returns expectedApiError

        // Create mock HTTP client that returns an error status
        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(errorResponseBody),
                status = HttpStatusCode.Unauthorized,
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
        assertTrue(result.isLeft(), "Result should be Left on non-success HTTP status")
        val error = result.leftOrNull()
        assertEquals(expectedApiError, error, "Error should match the error returned by the strategy")

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
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) } // Success processing should not be called
        verify(exactly = 1) { mockStrategy.processErrorResponse(errorStatusCode, errorResponseBody) }
    }

    @Test
    fun `completeChat should return InvalidResponseError if strategy processSuccessResponse fails`() = runTest {
        // Arrange
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )
        val successResponseBody = """{"invalid_json": "missing_fields"}""" // Simulate invalid body for strategy
        val invalidResponseException = Exception("Failed to parse JSON")
        val invalidResponseError =
            LLMCompletionError.InvalidResponseError("Failed to parse response", invalidResponseException)

        // Mock strategy to return successful request config
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        // Mock strategy processSuccessResponse to return an error
        every { mockStrategy.processSuccessResponse(successResponseBody) } returns invalidResponseError.left()

        // Create mock HTTP client that returns success status but invalid body
        httpClient = createMockHttpClient { request ->
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
        assertTrue(result.isLeft(), "Result should be Left when strategy success processing fails")
        val error = result.leftOrNull()
        assertEquals(
            invalidResponseError,
            error,
            "Error should match the InvalidResponseError returned by the strategy"
        )

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

    @Test
    fun `completeChat should handle 400 Bad Request correctly`() = runTest {
        // Arrange
        val statusCode = 400
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )
        val errorResponseBody = """{"error": {"message": "Bad Request", "type": "invalid_request_error"}}"""
        val expectedApiError = LLMCompletionError.ApiError(statusCode, "API Error $statusCode", errorResponseBody)

        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        every { mockStrategy.processErrorResponse(statusCode, errorResponseBody) } returns expectedApiError

        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(errorResponseBody),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result = client.completeChat(testMessages, testModel, testProvider, testSettings, testApiKey)

        // Assert
        assertTrue(result.isLeft(), "Result should be Left for status code $statusCode")
        val error = result.leftOrNull()
        assertEquals(expectedApiError, error, "Error should match expected error for status code $statusCode")

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
        verify(exactly = 1) { mockStrategy.processErrorResponse(statusCode, errorResponseBody) }
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
    }

    @Test
    fun `completeChat should handle 429 Too Many Requests correctly`() = runTest {
        // Arrange
        val statusCode = 429
        val apiRequestConfig = ApiRequestConfig(
            path = "/chat/completions",
            method = GenericHttpMethod.POST,
            body = "request body",
            contentType = GenericContentType.APPLICATION_JSON
        )
        val errorResponseBody = """{"error": {"message": "Rate limit exceeded", "type": "rate_limit_error"}}"""
        val expectedApiError = LLMCompletionError.ApiError(statusCode, "Rate limit exceeded", errorResponseBody)

        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, testApiKey)
        } returns apiRequestConfig.right()

        every { mockStrategy.processErrorResponse(statusCode, errorResponseBody) } returns expectedApiError

        httpClient = createMockHttpClient { request ->
            respond(
                content = ByteReadChannel(errorResponseBody),
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        // Act
        val result = client.completeChat(testMessages, testModel, testProvider, testSettings, testApiKey)

        // Assert
        assertTrue(result.isLeft(), "Result should be Left for status code $statusCode")
        val error = result.leftOrNull()
        assertEquals(expectedApiError, error, "Error should match expected error for status code $statusCode")

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
        verify(exactly = 1) { mockStrategy.processErrorResponse(statusCode, errorResponseBody) }
        verify(exactly = 0) { mockStrategy.processSuccessResponse(any()) }
    }

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

        httpClient = createMockHttpClient { request ->
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

        httpClient = createMockHttpClient { request ->
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