package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    private val testSettings = ChatModelSettings(
        id = 1L,
        modelId = testModel.id,
        name = "Default",
        temperature = 0.7f,
        maxTokens = 1000,
        stream = false, // Configure for non-streaming mode
        customParams = null
    )

    private val testMessages = listOf(
        RawChatMessage.User(
            content = "Hello, how are you?"
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
            apiKey = null,
            systemMessage = "You are a helpful assistant."
        )

        // Assert
        assertTrue(result.isRight(), "Expected successful request preparation")
        val config = result.getOrElse { throw AssertionError("Expected ApiRequestConfig") }

        assertEquals("/api/chat", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertTrue(config.customHeaders.isEmpty(), "Ollama should not require custom headers")

        // Verify the request body structure by parsing the JSON string
        val requestBodyString = config.body as String
        val requestBody = json.decodeFromString<OllamaApiModels.ChatCompletionRequest>(requestBodyString)

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
        val settingsWithoutSystem = testSettings

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

        // Verify the request body structure by parsing the JSON string
        val requestBodyString = config.body as String
        val requestBody = json.decodeFromString<OllamaApiModels.ChatCompletionRequest>(requestBodyString)

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
        assertEquals(404, result.statusCode)
        assertTrue(result.message!!.contains("model 'nonexistent' not found"))
        assertEquals(errorBody, result.errorBody)
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
        assertEquals(500, result.statusCode)
        assertTrue(result.message!!.contains("Invalid JSON response"))
    }

    @Test
    fun `strategy should have correct provider type`() {
        assertEquals(LLMProviderType.OLLAMA, strategy.providerType)
    }

    /**
     * Verifies that a generation the server stopped at the model's output limit is reported as a failure on the
     * result, without displacing the partial answer the same body carried.
     */
    @Test
    fun `processSuccessResponse reports a length terminal reason as a failure that keeps the content`() {
        val responseBody = """
            {
                "model": "llama3.2",
                "created_at": "2023-12-07T09:32:18.757212583-08:00",
                "message": { "role": "assistant", "content": "Partial answer" },
                "done": true,
                "done_reason": "length",
                "prompt_eval_count": 26,
                "eval_count": 15
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        val completionResult = assertNotNull(result.getOrNull())
        assertEquals("Partial answer", completionResult.choices.single().content)
        val providerFailure = assertNotNull(completionResult.providerFailure)
        assertEquals("length", providerFailure.providerCode)
    }

    /**
     * Verifies that a normally finished generation declares no ending, both when the server reports the reason and
     * when it omits the field entirely.
     */
    @Test
    fun `processSuccessResponse declares no ending without a non-success terminal reason`() {
        val stoppedBody = """
            {
                "model": "llama3.2",
                "created_at": "2023-12-07T09:32:18.757212583-08:00",
                "message": { "role": "assistant", "content": "A complete answer" },
                "done": true,
                "done_reason": "stop"
            }
        """.trimIndent()
        // Older and Ollama-compatible servers omit the field; `done` alone must not be read as a truncation.
        val reasonlessBody = """
            {
                "model": "llama3.2",
                "created_at": "2023-12-07T09:32:18.757212583-08:00",
                "message": { "role": "assistant", "content": "A complete answer" },
                "done": true
            }
        """.trimIndent()

        listOf(stoppedBody, reasonlessBody).forEach { responseBody ->
            val completionResult = assertNotNull(strategy.processSuccessResponse(responseBody).getOrNull())
            assertEquals("A complete answer", completionResult.choices.single().content)
            assertNull(completionResult.providerFailure, "A normally finished generation declares no ending")
        }
    }

    /**
     * Verifies the streaming order: the failure of a cut-off generation is emitted after the content of the same
     * generation and before the terminal chunk, so a consumer that keeps the first ending records the truncation.
     */
    @Test
    fun `processStreamingResponse emits a length terminal reason before the terminal chunk`() = runTest {
        val streamLines = listOf(
            "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"Partial answer\"},\"done\":false}",
            "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                "\"done_reason\":\"length\",\"prompt_eval_count\":26,\"eval_count\":15}"
        )

        val emitted = strategy.processStreamingResponse(flowOf(*streamLines.toTypedArray()))
            .toList()
            .mapNotNull { it.getOrNull() }

        val contentIndex = emitted.indexOfFirst {
            it is LLMStreamChunk.ContentChunk && it.deltaContent == "Partial answer"
        }
        val errorIndex = emitted.indexOfFirst { it is LLMStreamChunk.Error }
        val doneIndex = emitted.indexOfFirst { it is LLMStreamChunk.Done }
        assertTrue(contentIndex >= 0, "The partial answer of the cut-off generation must be emitted")
        assertTrue(errorIndex in (contentIndex + 1) until doneIndex, "The failure must sit between content and Done")
        val errorChunk = assertIs<LLMStreamChunk.Error>(emitted[errorIndex])
        val providerFailure = assertIs<LLMCompletionError.ProviderFailureError>(errorChunk.llmError)
        assertEquals("length", providerFailure.providerCode)
        // The usage of the terminal chunk is still reported, ahead of the failure it qualifies.
        assertTrue(emitted.any { it is LLMStreamChunk.UsageChunk })
    }

    /**
     * Verifies that a streaming generation with a normal or absent terminal reason ends without any failure chunk.
     */
    @Test
    fun `processStreamingResponse declares no failure without a non-success terminal reason`() = runTest {
        val stoppedLines = listOf(
            "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"Complete\"},\"done\":false}",
            "{\"model\":\"llama3.2\",\"created_at\":\"2023-12-07T09:32:18Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}"
        )
        val reasonlessLines = stoppedLines.map { line ->
            line.replace(",\"done_reason\":\"stop\"", "")
        }

        listOf(stoppedLines, reasonlessLines).forEach { streamLines ->
            val emitted = strategy.processStreamingResponse(flowOf(*streamLines.toTypedArray()))
                .toList()
                .mapNotNull { it.getOrNull() }

            assertEquals(
                listOf("Complete"),
                emitted.filterIsInstance<LLMStreamChunk.ContentChunk>().map { it.deltaContent }
            )
            assertTrue(emitted.none { it is LLMStreamChunk.Error }, "A finished generation is not a failure")
            assertTrue(emitted.any { it is LLMStreamChunk.Done })
        }
    }

    /**
     * Verifies the input projection shared with the token counter equals the input-bearing fields
     * actually embedded in the prepared request body, so counting can never drift from the payload.
     */
    @Test
    fun `buildInputProjection equals the input-bearing fields of the prepared request`() {
        val systemMessage = "You are a helpful assistant."
        val prepared = strategy.prepareRequest(
            messages = testMessages,
            modelConfig = testModel,
            provider = testProvider,
            settings = testSettings,
            apiKey = null,
            systemMessage = systemMessage
        ).getOrElse { throw AssertionError("Expected successful request preparation") }
        val projection = strategy.buildInputProjection(
            messages = testMessages,
            modelConfig = testModel,
            provider = testProvider,
            settings = testSettings,
            systemMessage = systemMessage
        ).getOrElse { throw AssertionError("Expected successful projection") }

        val body = json.decodeFromString<JsonObject>(prepared.body as String)
        // Only input-bearing fields (messages and optional tools) are part of the projection.
        val inputFields = body.filterKeys { it == "messages" || it == "tools" }
        assertEquals(json.encodeToString(projection), json.encodeToString(inputFields))
        // Generation options are not input: the projection must not contain model/stream/options.
        assertFalse(projection.containsKey("model"))
        assertFalse(projection.containsKey("stream"))
        assertFalse(projection.containsKey("options"))
    }
}
