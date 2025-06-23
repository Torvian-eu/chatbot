package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.*

@DisplayName("OpenAIChatStrategy Tests")
class OpenAIChatStrategyTest {

    private lateinit var mockJson: Json
    private lateinit var strategy: OpenAIChatStrategy

    @BeforeEach
    fun setUp() {
        mockJson = Json { ignoreUnknownKeys = true } // Configure Json as needed
        strategy = OpenAIChatStrategy(mockJson)
    }

    // --- prepareRequest Tests ---

    @Test
    @DisplayName("prepareRequest should successfully create ApiRequestConfig with API key")
    fun prepareRequest_successWithApiKey() {
        // Given
        val messages = listOf(
            ChatMessage.UserMessage(1, 1, "Hello", TestDefaults.DEFAULT_INSTANT, TestDefaults.DEFAULT_INSTANT, null),
            ChatMessage.AssistantMessage(
                2,
                1,
                "Hi there!",
                TestDefaults.DEFAULT_INSTANT,
                TestDefaults.DEFAULT_INSTANT,
                1,
                modelId = 1,
                settingsId = 1
            ),
            ChatMessage.UserMessage(
                3,
                1,
                "Tell me a story",
                TestDefaults.DEFAULT_INSTANT,
                TestDefaults.DEFAULT_INSTANT,
                2
            )
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-4o") // Use a specific model name
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "test-key-id", baseUrl = "https://api.openai.com/v1")
        val settings = TestDefaults.modelSettings1.copy(
            systemMessage = "You are a helpful assistant.", // Add system message
            temperature = 0.9f,
            maxTokens = 500,
            customParamsJson = """{"top_p": 0.8, "frequency_penalty": 0.2, "stop": ["\nUser:", "<|end_of_text|>"]}"""
        )
        val apiKey = "sk-test-api-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        assertEquals("/chat/completions", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertEquals("Bearer $apiKey", config.customHeaders[HttpHeaders.Authorization])
        assertFalse(config.customHeaders.containsKey("X-Api-Key"), "Should not use X-Api-Key header for OpenAI")

        // Verify the body structure and content
        assertTrue(
            config.body is OpenAiApiModels.ChatCompletionRequest,
            "Body should be OpenAiApiModels.ChatCompletionRequest"
        )
        val requestBody = config.body

        assertEquals(modelConfig.name, requestBody.model)
        assertEquals(settings.temperature, requestBody.temperature)
        assertEquals(settings.maxTokens, requestBody.max_tokens)

        // Verify custom params mapping
        assertEquals(0.8f, requestBody.top_p)
        assertEquals(0.2f, requestBody.frequency_penalty)
        assertNull(requestBody.presence_penalty) // Not in customParamsJson
        assertEquals(listOf("\nUser:", "<|end_of_text|>"), requestBody.stop)

        // Verify messages mapping
        assertEquals(4, requestBody.messages.size) // 1 system + 3 chat messages

        // Assert system message is first
        assertEquals("system", requestBody.messages[0].role)
        assertEquals(settings.systemMessage, requestBody.messages[0].content)

        // Assert original chat messages follow
        assertEquals("user", requestBody.messages[1].role)
        assertEquals("Hello", requestBody.messages[1].content)
        assertEquals("assistant", requestBody.messages[2].role)
        assertEquals("Hi there!", requestBody.messages[2].content)
        assertEquals("user", requestBody.messages[3].role)
        assertEquals("Tell me a story", requestBody.messages[3].content)
    }

    @Test
    @DisplayName("prepareRequest should successfully create ApiRequestConfig without API key if apiKeyId is null")
    fun prepareRequest_successWithoutApiKeyIfApiKeyIdNull() {
        // Given
        val messages = listOf(
            ChatMessage.UserMessage(
                1,
                1,
                "Test message",
                TestDefaults.DEFAULT_INSTANT,
                TestDefaults.DEFAULT_INSTANT,
                null
            )
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "local-model")
        val provider =
            TestDefaults.llmProvider1.copy(apiKeyId = null, baseUrl = "http://localhost:8000") // apiKeyId is null
        val settings = TestDefaults.modelSettings1.copy(temperature = 0.5f)
        val apiKey = null // API key is null

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        assertEquals("/chat/completions", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertFalse(
            config.customHeaders.containsKey(HttpHeaders.Authorization),
            "Should not include Authorization header if apiKey is null"
        )

        assertTrue(
            config.body is OpenAiApiModels.ChatCompletionRequest,
            "Body should be OpenAiApiModels.ChatCompletionRequest"
        )
        val requestBody = config.body
        assertEquals(modelConfig.name, requestBody.model)
        assertEquals(settings.temperature, requestBody.temperature)
        assertEquals(2, requestBody.messages.size) // 1 system + 1 user message
    }


    @Test
    @DisplayName("prepareRequest should return ConfigurationError if apiKeyId is not null but apiKey is null")
    fun prepareRequest_errorIfApiKeyRequiredButNull() {
        // Given
        val messages = listOf(
            ChatMessage.UserMessage(
                1,
                1,
                "Test message",
                TestDefaults.DEFAULT_INSTANT,
                TestDefaults.DEFAULT_INSTANT,
                null
            )
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-4o")
        val provider = TestDefaults.llmProvider1.copy(
            apiKeyId = "required-key-id",
            baseUrl = "https://api.openai.com/v1"
        ) // apiKeyId is NOT null
        val settings = TestDefaults.modelSettings1.copy(temperature = 0.5f)
        val apiKey = null // API key is null

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey)

        // Then
        assertTrue(result.isLeft(), "Expected error result")
        val error = result.leftOrNull()
        assertTrue(error is LLMCompletionError.ConfigurationError, "Expected ConfigurationError")
        assertTrue(
            error.message.contains("requires an API key"),
            "Error message should indicate missing API key"
        )
    }

    // --- processSuccessResponse Tests ---

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
        assertTrue(error is LLMCompletionError.InvalidResponseError, "Expected InvalidResponseError")
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
        assertTrue(error is LLMCompletionError.InvalidResponseError, "Expected InvalidResponseError")
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
        assertTrue(error is LLMCompletionError.AuthenticationError, "Expected AuthenticationError")
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
        assertTrue(error is LLMCompletionError.ApiError, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertTrue(
            error.message?.contains("Invalid value for 'messages[0].role'") == true,
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
        assertTrue(error is LLMCompletionError.ApiError, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertTrue(
            error.message?.contains("You exceeded your current quota") == true,
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
        assertTrue(error is LLMCompletionError.ApiError, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertTrue(
            error.message?.contains("The server had an error") == true,
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
        assertTrue(error is LLMCompletionError.ApiError, "Expected ApiError")
        assertEquals(statusCode, error.statusCode)
        assertTrue(
            error.message?.contains("OpenAI API returned error $statusCode") == true,
            "Error message should indicate status code"
        )
        // Message should contain a snippet of the raw body if parsing failed
        assertTrue(
            error.message.contains(errorBody.take(200)),
            "Error message should contain snippet of raw body"
        )
        assertEquals(errorBody, error.errorBody)
    }
}