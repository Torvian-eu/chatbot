package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.ktor.http.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Verifies [ResponsesStrategy] request preparation, success-response parsing, and streaming handling
 * against the generic strategy boundary.
 */
@DisplayName("ResponsesStrategy tests")
class ResponsesStrategyTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val strategy = ResponsesStrategy(json)

    private val responsesModel = TestDefaults.llmModel1.copy(
        name = "gpt-5.4",
        type = LLMModelType.RESPONSES
    )

    private val responsesSettings = ResponsesModelSettings(
        id = 1L,
        modelId = responsesModel.id,
        name = "Default Responses Settings",
        instructions = "You are a helpful assistant.",
        temperature = 0.8f,
        maxOutputTokens = 200,
        reasoningEffort = "high",
        store = true
    )

    @Test
    @DisplayName("prepareRequest should build a Responses request with item-based input")
    fun prepareRequest_buildsResponsesBody() {
        val messages = listOf(
            RawChatMessage.User("Hello"),
            RawChatMessage.Assistant("Hi there!"),
            RawChatMessage.Tool(content = """{"result":42}""", toolCallId = "call_1", name = "getWeather")
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")
        val apiKey = "sk-test"

        val result = strategy.prepareRequest(messages, responsesModel, provider, responsesSettings, apiKey)

        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config)

        assertEquals("/responses", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertEquals("Bearer $apiKey", config.customHeaders[HttpHeaders.Authorization])

        val body = Json.decodeFromString<JsonObject>(config.body as String)
        assertEquals("gpt-5.4", body["model"]?.jsonPrimitive?.content)
        assertEquals("You are a helpful assistant.", body["instructions"]?.jsonPrimitive?.content)
        assertEquals(true, body["store"]?.jsonPrimitive?.boolean)
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)

        val input = body["input"]?.jsonArray
        assertNotNull(input, "input array should be present")
        assertEquals(3, input.size)

        // User item
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Hello", input[0].jsonObject["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)

        // Assistant text item
        assertEquals("assistant", input[1].jsonObject["role"]?.jsonPrimitive?.content)

        // Function call output item
        assertEquals("function_call_output", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", input[2].jsonObject["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("prepareRequest should fold assistant tool calls into function_call items")
    fun prepareRequest_mapsAssistantToolCalls() {
        val messages = listOf(
            RawChatMessage.Assistant(
                content = null,
                toolCalls = listOf(RawChatMessage.Assistant.ToolCall(id = "call_a", name = "getWeather", arguments = "{}"))
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")

        val result = strategy.prepareRequest(messages, responsesModel, provider, responsesSettings, "sk-test")

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val input = body["input"]?.jsonArray
        assertEquals(1, input?.size)
        assertEquals("function_call", input!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_a", input[0].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("getWeather", input[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("prepareRequest should require an API key when the provider has one")
    fun prepareRequest_missingApiKey_returnsConfigurationError() {
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")
        val result = strategy.prepareRequest(
            messages = listOf(RawChatMessage.User("Hello")),
            modelConfig = responsesModel,
            provider = provider,
            settings = responsesSettings,
            apiKey = null
        )
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is LLMCompletionError.ConfigurationError)
    }

    @Test
    @DisplayName("processSuccessResponse should map output items to a generic result")
    fun processSuccessResponse_mapsOutput() {
        val responseBody = """
            {
              "id": "resp_123",
              "object": "response",
              "status": "completed",
              "model": "gpt-5.4",
              "output": [
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [ { "type": "output_text", "text": "Hello there!" } ]
                },
                {
                  "type": "function_call",
                  "call_id": "call_1",
                  "name": "getWeather",
                  "arguments": "{\"city\":\"Paris\"}"
                }
              ],
              "reasoning": { "effort": "high", "summary": null },
              "usage": {
                "input_tokens": 15,
                "output_tokens": 25,
                "output_tokens_details": { "reasoning_tokens": 10 },
                "total_tokens": 40
              }
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        assertTrue(result.isRight(), "Expected parse success")
        val completion = result.getOrNull()
        assertNotNull(completion)

        assertEquals("resp_123", completion.id)
        assertEquals(1, completion.choices.size)
        val choice = completion.choices.first()
        assertEquals("Hello there!", choice.content)
        assertEquals("tool_calls", choice.finishReason, "Tool calls present -> tool_calls finish reason")
        assertEquals(1, choice.toolCalls?.size)
        assertEquals("call_1", choice.toolCalls!![0].toolCallId)
        assertEquals("getWeather", choice.toolCalls[0].name)
        assertEquals(15, completion.usage.promptTokens)
        assertEquals(25, completion.usage.completionTokens)
        assertEquals(40, completion.usage.totalTokens)
        assertEquals(10, completion.metadata["reasoning_tokens"])
    }

    @Test
    @DisplayName("processSuccessResponse should treat a plain text response as stop")
    fun processSuccessResponse_plainTextStops() {
        val responseBody = """
            {
              "id": "resp_2",
              "object": "response",
              "status": "completed",
              "model": "gpt-5.4",
              "output": [
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [ { "type": "output_text", "text": "Just text." } ]
                }
              ],
              "usage": {
                "input_tokens": 1,
                "output_tokens": 3,
                "total_tokens": 4
              }
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        assertTrue(result.isRight())
        val choice = result.getOrNull()!!.choices.first()
        assertEquals("Just text.", choice.content)
        assertEquals("stop", choice.finishReason)
        assertNull(choice.toolCalls)
    }

    @Test
    @DisplayName("processStreamingResponse should emit content, tool-call and done chunks")
    fun processStreamingResponse_emitsChunks() = runBlocking {
        val events = flowOf(
            """data: {"type":"response.output_text.delta","delta":"Hi"}""",
            """data: {"type":"response.function_call_arguments.delta","call_id":"call_1","name":"getWeather","delta":"{\"city\":"}""",
            """data: {"type":"response.output_text.delta","delta":" there!"}""",
            """data: {"type":"response.completed","response":{"id":"resp_9","usage":{"input_tokens":5,"output_tokens":7,"total_tokens":12}}}""",
            """data: [DONE]"""
        )

        val chunks = mutableListOf<LLMStreamChunk>()
        strategy.processStreamingResponse(events).collect { either ->
            either.fold(
                ifLeft = { throw AssertionError("Expected no error, got $it") },
                ifRight = { chunks.add(it) }
            )
        }

        assertTrue(chunks.isNotEmpty(), "Expected at least one emitted chunk")
        assertTrue(chunks.any { it is LLMStreamChunk.ContentChunk && it.deltaContent == "Hi" })
        assertTrue(chunks.any { it is LLMStreamChunk.ContentChunk && it.deltaContent == " there!" })
        assertTrue(chunks.any { it is LLMStreamChunk.ToolCallChunk && it.id == "call_1" })
        assertTrue(chunks.any { it is LLMStreamChunk.UsageChunk && it.totalTokens == 12 })
    }

    @Test
    @DisplayName("processErrorResponse should surface auth failures")
    fun processErrorResponse_mapsAuthError() {
        val error = strategy.processErrorResponse(401, """{"error":{"message":"Invalid key"}}""")
        assertTrue(error is LLMCompletionError.AuthenticationError)
    }
}
