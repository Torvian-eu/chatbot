package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.common.models.llm.LLMModelCapabilities
import eu.torvian.chatbot.common.models.llm.LLMProviderType
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
        name = "gpt-5.4"
    )

    private val responsesSettings = ResponsesModelSettings(
        id = 1L,
        modelId = responsesModel.id,
        name = "Default Responses Settings",
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

        val result = strategy.prepareRequest(messages, responsesModel, provider, responsesSettings, apiKey, systemMessage = "You are a helpful assistant.")

        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config)

        assertEquals("/responses", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertEquals("Bearer $apiKey", config.customHeaders[HttpHeaders.Authorization])
        // OpenAI must not receive OpenRouter attribution headers.
        assertFalse(config.customHeaders.containsKey("HTTP-Referer"))
        assertFalse(config.customHeaders.containsKey("X-OpenRouter-Title"))
        assertFalse(config.customHeaders.containsKey("X-OpenRouter-Categories"))

        val body = Json.decodeFromString<JsonObject>(config.body as String)
        assertEquals("gpt-5.4", body["model"]?.jsonPrimitive?.content)
        assertEquals("You are a helpful assistant.", body["instructions"]?.jsonPrimitive?.content)
        // The `store` knob is honored for OpenAI: `responsesSettings.store` is true, so the response
        // is stored server-side and may be referenced via `previous_response_id` in later turns.
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
    @DisplayName("prepareRequest should honor the store setting for OpenAI providers")
    fun prepareRequest_openAiHonorsStoreSetting() {
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")
        val noStoreSettings = responsesSettings.copy(store = false)

        val result = strategy.prepareRequest(
            messages = listOf(RawChatMessage.User("Hello")),
            modelConfig = responsesModel,
            provider = provider,
            settings = noStoreSettings,
            apiKey = "sk-test"
        )

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
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

        val result = strategy.prepareRequest(messages, responsesModel, provider, responsesSettings, "sk-test", systemMessage = "You are a helpful assistant.")

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val input = body["input"]?.jsonArray
        assertEquals(1, input?.size)
        assertEquals("function_call", input!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_a", input[0].jsonObject["call_id"]?.jsonPrimitive?.content)
        assertEquals("getWeather", input[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("prepareRequest should add OpenRouter attribution headers for OpenRouter providers")
    fun prepareRequest_openRouterAddsAttributionHeaders() {
        val provider = TestDefaults.llmProvider1.copy(
            name = "OpenRouter",
            type = LLMProviderType.OPENROUTER,
            apiKeyId = "openrouter-key",
            baseUrl = "https://openrouter.ai/api/v1"
        )
        val apiKey = "sk-openrouter-test"

        val result = strategy.prepareRequest(
            messages = listOf(RawChatMessage.User("Hello")),
            modelConfig = responsesModel.copy(name = "openai/gpt-5.4"),
            provider = provider,
            settings = responsesSettings,
            apiKey = apiKey
        )

        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")
        assertEquals("Bearer $apiKey", config.customHeaders[HttpHeaders.Authorization])
        assertEquals("https://chatbot.torvian.eu", config.customHeaders["HTTP-Referer"])
        assertEquals("Torvian Chatbot", config.customHeaders["X-OpenRouter-Title"])
        assertEquals("cloud-agent,general-chat", config.customHeaders["X-OpenRouter-Categories"])

        val body = Json.decodeFromString<JsonObject>(config.body as String)
        assertEquals("openai/gpt-5.4", body["model"]?.jsonPrimitive?.content)
        assertEquals(1, body["input"]?.jsonArray?.size)
        // OpenRouter rejects `store:true`, so it is forced off even though `responsesSettings.store` is true.
        assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
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
        assertEquals(10, completion.usage.reasoningTokens)
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
    @DisplayName("processStreamingResponse should aggregate tool calls by output_index and carry name/call_id")
    fun processStreamingResponse_emitsToolCallChunksByOutputIndex() = runBlocking {
        val events = flowOf(
            """data: {"type":"response.output_text.delta","delta":"Hi"}""",
            """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"findFiles"}}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"pattern\":"}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"\"*.py\"}"}""",
            """data: {"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"findFiles"}}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_2","output_index":1,"delta":"{\"path\":\"chatbot\"}"}""",
            """data: {"type":"response.output_text.delta","delta":" there!"}""",
            """data: {"type":"response.completed","response":{"id":"resp_9","usage":{"input_tokens":5,"output_tokens":7,"output_tokens_details":{"reasoning_tokens":10},"total_tokens":12}}}""",
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
        assertTrue(chunks.any {
            it is LLMStreamChunk.UsageChunk && it.totalTokens == 12 && it.reasoningTokens == 10
        })

        // Both tool calls must be grouped by distinct output_index, each carrying its name and call_id,
        // and no arguments may bleed across tool calls.
        val toolCallChunks = chunks.filterIsInstance<LLMStreamChunk.ToolCallChunk>()
        assertEquals(3, toolCallChunks.size)
        assertTrue(toolCallChunks.all { it.name == "findFiles" })

        val indexed = toolCallChunks.groupBy { it.index }
        assertEquals(setOf(0, 1), indexed.keys)
        assertTrue(indexed[0]!!.all { it.id == "call_1" })
        assertTrue(indexed[1]!!.all { it.id == "call_2" })
        assertEquals("""{"pattern":"*.py"}""", indexed[0]!!.joinToString("") { it.argumentsDelta.orEmpty() })
        assertEquals("""{"path":"chatbot"}""", indexed[1]!!.joinToString("") { it.argumentsDelta.orEmpty() })
    }

    @Test
    @DisplayName("processStreamingResponse should emit ToolCallDone for a completed function_call item")
    fun processStreamingResponse_emitsToolCallDone() = runBlocking {
        val events = flowOf(
            """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"getWeather"}}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"loc\""}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":":\"Paris\"}"}""",
            // The final item may carry a corrected/authoritative arguments string.
            """data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"getWeather","arguments":"{\"location\":\"Paris\"}"}}""",
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

        val toolCallDones = chunks.filterIsInstance<LLMStreamChunk.ToolCallDone>()
        assertEquals(1, toolCallDones.size, "Expected a single ToolCallDone")
        assertEquals(0, toolCallDones[0].index)
        assertEquals("call_1", toolCallDones[0].id)
        assertEquals("getWeather", toolCallDones[0].name)
        assertEquals("""{"location":"Paris"}""", toolCallDones[0].arguments)
        // Deltas are still emitted for live UI streaming.
        assertTrue(chunks.any { it is LLMStreamChunk.ToolCallChunk })
    }

    @Test
    @DisplayName("processStreamingResponse should map output_index to a sequential tool-call index")
    fun processStreamingResponse_mapsOutputIndexToSequentialIndex() = runBlocking {
        // The Responses API output_index is a position in the whole output[] array; here it starts at 2
        // because two reasoning items precede the first function call.
        val events = flowOf(
            """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
            """data: {"type":"response.output_item.added","output_index":1,"item":{"type":"reasoning","id":"rs_2"}}""",
            """data: {"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"findFiles"}}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":2,"delta":"{\"pattern\":\"*.py\"}"}""",
            """data: {"type":"response.output_item.added","output_index":3,"item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"readFile"}}""",
            """data: {"type":"response.function_call_arguments.delta","item_id":"fc_2","output_index":3,"delta":"{\"path\":\"x\"}"}""",
            """data: {"type":"response.output_item.done","output_index":2,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"findFiles","arguments":"{\"pattern\":\"*.py\"}"}}""",
            """data: {"type":"response.output_item.done","output_index":3,"item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"readFile","arguments":"{\"path\":\"x\"}"}}""",
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

        // Deltas must be keyed by sequential 0-based index, not the provider's output_index.
        val toolCallChunks = chunks.filterIsInstance<LLMStreamChunk.ToolCallChunk>()
        val indexed = toolCallChunks.groupBy { it.index }
        assertEquals(setOf(0, 1), indexed.keys)
        assertTrue(indexed[0]!!.all { it.id == "call_1" })
        assertTrue(indexed[1]!!.all { it.id == "call_2" })
        assertEquals("""{"pattern":"*.py"}""", indexed[0]!!.joinToString("") { it.argumentsDelta.orEmpty() })
        assertEquals("""{"path":"x"}""", indexed[1]!!.joinToString("") { it.argumentsDelta.orEmpty() })

        // The authoritative ToolCallDone must use the same sequential indices.
        val dones = chunks.filterIsInstance<LLMStreamChunk.ToolCallDone>()
        assertEquals(2, dones.size)
        assertEquals(listOf(0, 1), dones.map { it.index })
        assertEquals(listOf("call_1", "call_2"), dones.map { it.id })
    }

    @Test
    @DisplayName("processStreamingResponse should emit content, and done chunks")
    fun processStreamingResponse_emitsContentChunks() = runBlocking {
        val events = flowOf(
            """data: {"type":"response.output_text.delta","delta":"Hi"}""",
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
        assertTrue(chunks.any { it is LLMStreamChunk.UsageChunk && it.totalTokens == 12 })
    }

    @Test
    @DisplayName("processErrorResponse should surface auth failures")
    fun processErrorResponse_mapsAuthError() {
        val error = strategy.processErrorResponse(401, """{"error":{"message":"Invalid key"}}""")
        assertTrue(error is LLMCompletionError.AuthenticationError)
    }

    @Test
    @DisplayName("prepareRequest should inject sanitized reasoning items before their assistant message when replay is enabled")
    fun prepareRequest_injectsReasoningBeforeAssistant() {
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("status", JsonPrimitive("completed"))
            put("format", JsonPrimitive("unknown"))
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("summary_text"))
                    put("text", JsonPrimitive("Thinking summary."))
                })
            })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("reasoning_text"))
                    put("text", JsonPrimitive("Chain of thought."))
                })
            })
        }
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                toolCalls = null,
                reasoningItems = listOf(reasoningItem)
            ),
            RawChatMessage.User("Follow-up")
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")
        val replaySettings = responsesSettings.copy(replayReasoning = true)

        val result = strategy.prepareRequest(messages, responsesModel, provider, replaySettings, "sk-test", systemMessage = "You are a helpful assistant.")

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val input = body["input"]?.jsonArray
        assertNotNull(input)

        // user, reasoning, assistant, user
        assertEquals(4, input.size)
        val replayedReasoning = input[1].jsonObject
        assertEquals("reasoning", replayedReasoning["type"]?.jsonPrimitive?.content)
        assertEquals("rs_1", replayedReasoning["id"]?.jsonPrimitive?.content)
        // Output-only/provider-specific fields must be stripped so OpenAI does not reject the item.
        assertNull(replayedReasoning["status"])
        assertNull(replayedReasoning["format"])
        // `responsesModel` has no REASONING_ENCRYPTED capability, so the capability is unknown
        // and we replay the item as-is (unknown capability defaults to replay).
        assertNull(replayedReasoning["encrypted_content"], "plaintext items carry no encrypted_content")
        assertEquals("summary_text", replayedReasoning["summary"]?.jsonArray?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("Chain of thought.", replayedReasoning["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertEquals("assistant", input[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Let me think.", input[2].jsonObject["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("prepareRequest should replay an encrypted item to the same encrypted model")
    fun prepareRequest_encryptedSameModel_keepsEncryptedContent() {
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("summary_text"))
                    put("text", JsonPrimitive("Thinking summary."))
                })
            })
            // `content` and `encrypted_content` are mutually exclusive: encrypted items carry an
            // (empty) `content` array plus the opaque payload.
            put("content", buildJsonArray { })
            put("encrypted_content", JsonPrimitive("opaque-encrypted"))
        }
        val encryptedModel = responsesModel.copy(
            capabilities = buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(true))
            }
        )
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                reasoningItems = listOf(reasoningItem),
                // Same model as the target: the encrypted payload is replayable.
                reasoningModelId = encryptedModel.id
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")
        val replaySettings = responsesSettings.copy(replayReasoning = true)

        val result = strategy.prepareRequest(
            messages,
            encryptedModel,
            provider,
            replaySettings,
            "sk-test",
            systemMessage = "You are a helpful assistant."
        )

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val replayed = body["input"]?.jsonArray?.get(1)?.jsonObject
        assertNotNull(replayed)
        assertEquals("reasoning", replayed["type"]?.jsonPrimitive?.content)
        // Same-model encrypted payloads survive the round-trip for stateless replay.
        assertEquals("opaque-encrypted", replayed["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("Thinking summary.", replayed["summary"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        // The (empty) content array is harmless for encrypted-mode targets; it carries no plaintext.
        assertTrue(replayed["content"] == null || replayed["content"]?.jsonArray?.isEmpty() == true)
    }

    @Test
    @DisplayName("prepareRequest should not replay reasoning at all for an encrypted different-model target")
    fun prepareRequest_encryptedDifferentModel_dropsEncryptedContent() {
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("summary", buildJsonArray { })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("reasoning_text"))
                    put("text", JsonPrimitive("Chain of thought."))
                })
            })
            put("encrypted_content", JsonPrimitive("foreign-opaque"))
        }
        val encryptedModel = responsesModel.copy(
            capabilities = buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(true))
            }
        )
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                reasoningItems = listOf(reasoningItem),
                // Produced by a different model: the encrypted payload is NOT replayable.
                reasoningModelId = encryptedModel.id + 1
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")

        val result = strategy.prepareRequest(
            messages,
            encryptedModel,
            provider,
            responsesSettings.copy(replayReasoning = true),
            "sk-test",
            systemMessage = "You are a helpful assistant."
        )

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        // The foreign reasoning item must not be replayed at all: only user + assistant remain.
        val input = body["input"]?.jsonArray
        assertEquals(2, input?.size, "the foreign reasoning item must not be replayed to an encrypted-mode target")
        assertEquals(
            true,
            input?.none { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" },
            "no reasoning item may be present in the input"
        )
        assertEquals("user", input?.get(0)?.jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", input?.get(1)?.jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("prepareRequest should not replay reasoning at all for an encrypted different-model target, even with a summary")
    fun prepareRequest_encryptedDifferentModel_skipsReasoning() {
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("summary_text"))
                    put("text", JsonPrimitive("Foreign reasoning summary."))
                })
            })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("reasoning_text"))
                    put("text", JsonPrimitive("Chain of thought."))
                })
            })
            put("encrypted_content", JsonPrimitive("foreign-opaque"))
        }
        val encryptedModel = responsesModel.copy(
            capabilities = buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(true))
            }
        )
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                reasoningItems = listOf(reasoningItem),
                // Produced by a different model: only the exact producing model may receive an
                // encrypted payload, so the item is partial for this target and must be skipped.
                reasoningModelId = encryptedModel.id + 1
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")

        val result = strategy.prepareRequest(
            messages,
            encryptedModel,
            provider,
            responsesSettings.copy(replayReasoning = true),
            "sk-test",
            systemMessage = "You are a helpful assistant."
        )

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        // The foreign reasoning item must not be replayed at all, even with a summary: user + assistant only.
        val input = body["input"]?.jsonArray
        assertEquals(2, input?.size, "a foreign reasoning item must never be replayed to an encrypted-mode target")
        assertEquals(
            true,
            input?.none { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" },
            "no reasoning item may be present in the input"
        )
        assertEquals("user", input?.get(0)?.jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", input?.get(1)?.jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("prepareRequest replays reasoning items when model capability is unknown")
    fun prepareRequest_plaintextTarget_keepsContent() {
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("summary", buildJsonArray { })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("reasoning_text"))
                    put("text", JsonPrimitive("Chain of thought."))
                })
            })
        }
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                reasoningItems = listOf(reasoningItem),
                reasoningModelId = responsesModel.id
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")

        // `responsesModel` has no REASONING_ENCRYPTED capability: unknown mode defaults to plaintext.
        val result = strategy.prepareRequest(
            messages,
            responsesModel,
            provider,
            responsesSettings.copy(replayReasoning = true),
            "sk-test",
            systemMessage = "You are a helpful assistant."
        )

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val replayed = body["input"]?.jsonArray?.get(1)?.jsonObject
        assertNotNull(replayed)
        assertEquals("Chain of thought.", replayed["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertNull(replayed["encrypted_content"], "plaintext items carry no encrypted_content")
    }

    @Test
    @DisplayName("prepareRequest replays an encrypted-origin shell to a plaintext target (DeepSeek fix)")
    fun prepareRequest_plaintextTarget_skipsEncryptedOriginItemWithoutContent() {
        // An item produced by an encrypted-mode model has empty `content` and only the opaque payload.
        // Replayed to a plaintext target (explicit REASONING_ENCRYPTED = false, e.g. DeepSeek thinking
        // mode) it must still be sent with empty content so the provider sees a reasoning item.
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
            put("summary", buildJsonArray { })
            put("encrypted_content", JsonPrimitive("opaque-encrypted"))
        }
        // A model with explicitly plaintext reasoning mode.
        val plaintextModel = responsesModel.copy(
            capabilities = buildJsonObject {
                put(LLMModelCapabilities.REASONING_ENCRYPTED, JsonPrimitive(false))
            }
        )
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                reasoningItems = listOf(reasoningItem),
                reasoningModelId = plaintextModel.id
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")

        val result = strategy.prepareRequest(
            messages,
            plaintextModel,
            provider,
            responsesSettings.copy(replayReasoning = true),
            "sk-test",
            systemMessage = "You are a helpful assistant."
        )

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val input = body["input"]?.jsonArray
        // user, reasoning(shell), assistant
        assertEquals(3, input?.size)
        val replayed = input?.get(1)?.jsonObject
        assertNotNull(replayed)
        assertEquals("reasoning", replayed["type"]?.jsonPrimitive?.content)
        val contentArray = replayed["content"]?.jsonArray
        assertNotNull(contentArray)
        assertEquals(1, contentArray.size)
        assertEquals(" ", contentArray[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertNull(replayed["encrypted_content"], "encrypted_content is dropped for plaintext targets")
    }

    @Test
    @DisplayName("prepareRequest should not inject reasoning items when replay is disabled")
    fun prepareRequest_skipsReasoningWhenReplayDisabled() {
        val reasoningItem = buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_1"))
        }
        val messages = listOf(
            RawChatMessage.User("First question"),
            RawChatMessage.Assistant(
                content = "Let me think.",
                reasoningItems = listOf(reasoningItem)
            )
        )
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "openai-key", baseUrl = "https://api.openai.com/v1")
        val noReplaySettings = responsesSettings.copy(replayReasoning = false)

        val result = strategy.prepareRequest(messages, responsesModel, provider, noReplaySettings, "sk-test", systemMessage = "You are a helpful assistant.")

        assertTrue(result.isRight())
        val body = Json.decodeFromString<JsonObject>(result.getOrNull()!!.body as String)
        val input = body["input"]?.jsonArray
        assertNotNull(input)
        assertEquals(2, input.size, "Reasoning must be omitted when replayReasoning is false")
        assertTrue(input.all { it.jsonObject["type"]?.jsonPrimitive?.content != "reasoning" })
    }

    @Test
    @DisplayName("processSuccessResponse should capture raw reasoning items into the result")
    fun processSuccessResponse_capturesReasoningItems() {
        val responseBody = """
            {
              "id": "resp_r",
              "object": "response",
              "status": "completed",
              "model": "gpt-5.4",
              "output": [
                {
                  "type": "reasoning",
                  "id": "rs_1",
                  "status": "completed",
                  "summary": [ { "text": "Looking up the answer." } ],
                  "content": [ { "type": "reasoning_text", "text": "chain of thought" } ],
                  "encrypted_content": "opaque-ciphertext"
                },
                {
                  "type": "message",
                  "role": "assistant",
                  "content": [ { "type": "output_text", "text": "Here is the answer." } ]
                }
              ],
              "usage": { "input_tokens": 5, "output_tokens": 8, "total_tokens": 13 }
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(responseBody)

        assertTrue(result.isRight(), "Expected parse success")
        val completion = result.getOrNull()
        assertNotNull(completion)
        val reasoningItems = completion.reasoningItems
        assertNotNull(reasoningItems, "Reasoning items must be captured")
        assertEquals(1, reasoningItems.size)
        assertEquals("reasoning", reasoningItems[0]["type"]?.jsonPrimitive?.content)
        assertEquals("rs_1", reasoningItems[0]["id"]?.jsonPrimitive?.content)
        assertEquals("opaque-ciphertext", reasoningItems[0]["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("completed", reasoningItems[0]["status"]?.jsonPrimitive?.content)
        assertEquals("Here is the answer.", completion.choices[0].content.orEmpty())
    }

    @Test
    @DisplayName("processStreamingResponse should emit a ReasoningDone when a reasoning item completes")
    fun processStreamingResponse_emitsReasoningDone() = runBlocking {
        val events = flowOf(
            """data: {"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","status":"completed","encrypted_content":"opaque"}}""",
            """data: {"type":"response.output_text.delta","delta":"Hi"}""",
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

        assertTrue(chunks.any { it is LLMStreamChunk.ContentChunk && it.deltaContent == "Hi" })
        val reasoningDone = chunks.filterIsInstance<LLMStreamChunk.ReasoningDone>().singleOrNull()
        assertNotNull(reasoningDone, "Expected a single ReasoningDone")
        assertEquals("opaque", reasoningDone.reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertEquals("reasoning", reasoningDone.reasoningItem["type"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("processStreamingResponse should emit ReasoningTextChunk deltas for reasoning text")
    fun processStreamingResponse_emitsReasoningTextChunk() = runBlocking {
        val events = flowOf(
            """data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":"The","sequence_number":1}""",
            """data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":0,"content_index":0,"delta":" user","sequence_number":2}""",
            """data: {"type":"response.reasoning_text.delta","item_id":"rs_1","output_index":1,"content_index":0,"delta":"second","sequence_number":3}""",
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

        val reasoningChunks = chunks.filterIsInstance<LLMStreamChunk.ReasoningTextChunk>()
        assertEquals(3, reasoningChunks.size, "Expected three ReasoningTextChunks")
        // Deltas are emitted raw (not concatenated); the consumer groups/accumulates them.
        assertEquals("The", reasoningChunks[0].delta)
        assertEquals(0, reasoningChunks[0].outputIndex)
        assertEquals(0, reasoningChunks[0].contentIndex)
        assertEquals(" user", reasoningChunks[1].delta)
        assertEquals("second", reasoningChunks[2].delta)
        assertEquals(1, reasoningChunks[2].outputIndex)
    }
}
