package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.test.*

class OllamaChatStrategyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val strategy = OllamaChatStrategy(json)

    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = null, // Ollama typically doesn't need API keys
        name = "Ollama Local",
        description = "Local Ollama instance",
        baseUrl = "http://localhost:11434",
        type = LLMProviderType.OLLAMA
    )

    private val testModel = LLMModel(
        id = 1L,
        name = "llama3.2",
        providerId = testProvider.id,
        active = true,
        displayName = "Llama 3.2"
    )

    private val testSettings = ModelSettings(
        id = 1L,
        modelId = testModel.id,
        name = "Default",
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParamsJson = null
    )

    private val testMessages = listOf(
        ChatMessage.UserMessage(
            id = 1L,
            sessionId = 1L,
            content = "Hello, how are you?",
            createdAt = Instant.parse("2023-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2023-01-01T00:00:00Z"),
            parentMessageId = null,
            childrenMessageIds = emptyList()
        )
    )

    @Test
    fun `prepareRequest should create valid Ollama API request`() {
        // Act
        val result = strategy.prepareRequest(
            messages = testMessages,
            modelConfig = testModel,
            provider = testProvider,
            settings = testSettings,
            apiKey = null
        )

        // Assert
        assertTrue(result.isRight(), "Expected successful request preparation")
        val config = result.getOrElse { throw AssertionError("Expected ApiRequestConfig") }

        assertEquals("/api/chat", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertTrue(config.customHeaders.isEmpty(), "Ollama should not require custom headers")

        // Verify the request body structure
        val requestBody = config.body as OllamaApiModels.ChatCompletionRequest
        assertEquals("llama3.2", requestBody.model)
        assertEquals(false, requestBody.stream)
        assertEquals(2, requestBody.messages.size) // System message + user message

        // Check system message
        assertEquals("system", requestBody.messages[0].role)
        assertEquals("You are a helpful assistant.", requestBody.messages[0].content)

        // Check user message
        assertEquals("user", requestBody.messages[1].role)
        assertEquals("Hello, how are you?", requestBody.messages[1].content)

        // Check options
        assertNotNull(requestBody.options)
        assertEquals(0.7f, requestBody.options.temperature)
        assertEquals(1000, requestBody.options.num_predict)
    }

    @Test
    fun `prepareRequest should work without system message`() {
        // Arrange
        val settingsWithoutSystem = testSettings.copy(systemMessage = null)

        // Act
        val result = strategy.prepareRequest(
            messages = testMessages,
            modelConfig = testModel,
            provider = testProvider,
            settings = settingsWithoutSystem,
            apiKey = null
        )

        // Assert
        assertTrue(result.isRight())
        val config = result.getOrElse { throw AssertionError("Expected ApiRequestConfig") }
        val requestBody = config.body as OllamaApiModels.ChatCompletionRequest
        assertEquals(1, requestBody.messages.size) // Only user message
        assertEquals("user", requestBody.messages[0].role)
    }

    @Test
    fun `processSuccessResponse should parse Ollama response correctly`() {
        // Arrange
        val responseBody = """
            {
                "model": "llama3.2",
                "created_at": "2023-12-07T09:32:18.757212583-08:00",
                "message": {
                    "role": "assistant",
                    "content": "Hello! I'm doing well, thank you for asking. How can I help you today?"
                },
                "done": true,
                "total_duration": 4883583458,
                "load_duration": 1334875,
                "prompt_eval_count": 26,
                "prompt_eval_duration": 342546000,
                "eval_count": 15,
                "eval_duration": 4535599000
            }
        """.trimIndent()

        // Act
        val result = strategy.processSuccessResponse(responseBody)

        // Assert
        assertTrue(result.isRight(), "Expected successful response processing")
        val completionResult = result.getOrElse { throw AssertionError("Expected LLMCompletionResult") }

        assertNull(completionResult.id) // Ollama doesn't provide completion IDs
        assertEquals(1, completionResult.choices.size)

        val choice = completionResult.choices[0]
        assertEquals("assistant", choice.role)
        assertEquals("Hello! I'm doing well, thank you for asking. How can I help you today?", choice.content)
        assertEquals("stop", choice.finishReason)
        assertEquals(0, choice.index)

        // Check usage stats
        assertEquals(26, completionResult.usage.promptTokens)
        assertEquals(15, completionResult.usage.completionTokens)
        assertEquals(41, completionResult.usage.totalTokens)

        // Check metadata
        assertEquals("llama3.2", completionResult.metadata["api_model"])
        assertEquals(true, completionResult.metadata["api_done"])
    }

    @Test
    fun `processErrorResponse should handle Ollama error format`() {
        // Arrange
        val errorBody = """{"error": "model 'nonexistent' not found"}"""
        val statusCode = 404

        // Act
        val result = strategy.processErrorResponse(statusCode, errorBody)

        // Assert
        assertTrue(result is LLMCompletionError.ApiError)
        val apiError = result
        assertEquals(404, apiError.statusCode)
        assertTrue(apiError.message!!.contains("model 'nonexistent' not found"))
        assertEquals(errorBody, apiError.errorBody)
    }

    @Test
    fun `processErrorResponse should handle malformed error body`() {
        // Arrange
        val errorBody = "Invalid JSON response"
        val statusCode = 500

        // Act
        val result = strategy.processErrorResponse(statusCode, errorBody)

        // Assert
        assertTrue(result is LLMCompletionError.ApiError)
        val apiError = result
        assertEquals(500, apiError.statusCode)
        assertTrue(apiError.message!!.contains("Invalid JSON response"))
    }

    @Test
    fun `strategy should have correct provider type`() {
        assertEquals(LLMProviderType.OLLAMA, strategy.providerType)
    }
}
