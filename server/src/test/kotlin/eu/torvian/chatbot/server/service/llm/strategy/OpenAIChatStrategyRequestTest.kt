package eu.torvian.chatbot.server.service.llm.strategy

import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Verifies openaichatstrategy request preparation tests behavior at its public strategy boundary.
 */
@DisplayName("OpenAIChatStrategy request preparation tests")
class OpenAIChatStrategyRequestTest : OpenAIChatStrategyTestBase() {

    @Test
    @DisplayName("prepareRequest should successfully create ApiRequestConfig with API key")
    fun prepareRequest_successWithApiKey() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Hello"),
            RawChatMessage.Assistant("Hi there!"),
            RawChatMessage.User("Tell me a story")
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-4o") // Use a specific model name
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "test-key-id", baseUrl = "https://api.openai.com/v1")
        val settings = TestDefaults.modelSettings1.copy(
            systemMessage = "You are a helpful assistant.", // Add system message
            temperature = 0.9f,
            maxTokens = 500,
            customParams = Json.decodeFromString("""{"top_p": 0.8, "frequency_penalty": 0.2, "stop": ["\nUser:", "<|end_of_text|>"]}""")
        )
        val apiKey = "sk-test-api-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        assertEquals("/chat/completions", config.path)
        assertEquals(GenericHttpMethod.POST, config.method)
        assertEquals(GenericContentType.APPLICATION_JSON, config.contentType)
        assertEquals("Bearer $apiKey", config.customHeaders[HttpHeaders.Authorization])
        assertFalse(config.customHeaders.containsKey("X-Api-Key"), "Should not use X-Api-Key header for OpenAI")
        assertFalse(config.customHeaders.containsKey("HTTP-Referer"), "OpenAI must not receive OpenRouter attribution")
        assertFalse(
            config.customHeaders.containsKey("X-OpenRouter-Title"),
            "OpenAI must not receive OpenRouter attribution"
        )
        assertFalse(
            config.customHeaders.containsKey("X-OpenRouter-Categories"),
            "OpenAI must not receive OpenRouter attribution"
        )

        // Verify the body is now a String (pre-serialized JSON)
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body

        // Parse the JSON string back to verify its contents
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        assertEquals("gpt-4o", requestBodyJson["model"]?.jsonPrimitive?.content)
        assertEquals(0.9f, requestBodyJson["temperature"]?.jsonPrimitive?.float)
        assertEquals(500, requestBodyJson["max_tokens"]?.jsonPrimitive?.int)

        // Verify custom params mapping
        assertEquals(0.8f, requestBodyJson["top_p"]?.jsonPrimitive?.float)
        assertEquals(0.2f, requestBodyJson["frequency_penalty"]?.jsonPrimitive?.float)

        // Verify messages mapping
        val messagesArrayJson = requestBodyJson["messages"]?.jsonArray
        assertNotNull(messagesArrayJson, "Should have messages array")
        assertEquals(4, messagesArrayJson.size) // 1 system + 3 chat messages
    }

    /**
     * Verifies that OpenRouter receives the canonical attribution headers while the
     * OpenAI-compatible request body remains unchanged.
     */
    @Test
    @DisplayName("prepareRequest should add OpenRouter attribution headers without changing the payload")
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
            modelConfig = TestDefaults.llmModel1.copy(name = "openai/gpt-4o"),
            provider = provider,
            settings = TestDefaults.modelSettings1,
            apiKey = apiKey,
            systemMessage = TestDefaults.modelSettings1.systemMessage
        )

        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")
        assertEquals("Bearer $apiKey", config.customHeaders[HttpHeaders.Authorization])
        assertEquals("https://chatbot.torvian.eu", config.customHeaders["HTTP-Referer"])
        assertEquals("Torvian Chatbot", config.customHeaders["X-OpenRouter-Title"])
        assertEquals("cloud-agent,general-chat", config.customHeaders["X-OpenRouter-Categories"])

        val body = Json.decodeFromString<JsonObject>(config.body as String)
        assertEquals("openai/gpt-4o", body["model"]?.jsonPrimitive?.content)
        assertEquals(2, body["messages"]?.jsonArray?.size)
    }

    @Test
    @DisplayName("prepareRequest should successfully create ApiRequestConfig without API key if apiKeyId is null")
    fun prepareRequest_successWithoutApiKeyIfApiKeyIdNull() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Test message")
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "local-model")
        val provider =
            TestDefaults.llmProvider1.copy(apiKeyId = null, baseUrl = "http://localhost:8000") // apiKeyId is null
        val settings = TestDefaults.modelSettings1.copy(temperature = 0.5f)
        val apiKey = null // API key is null

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

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

        // Verify the body is now a String (pre-serialized JSON)
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body

        // Parse the JSON string back to verify its contents
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        assertEquals("local-model", requestBodyJson["model"]?.jsonPrimitive?.content)
        assertEquals(0.5f, requestBodyJson["temperature"]?.jsonPrimitive?.float)

        // Verify messages array has expected structure
        val messagesArray = requestBodyJson["messages"]?.jsonArray
        assertNotNull(messagesArray, "Should have messages array")
        // Should have system message + user message (2 total)
        assertTrue(messagesArray.isNotEmpty(), "Should have at least 1 message")
    }

    @Test
    @DisplayName("prepareRequest should return ConfigurationError if apiKeyId is not null but apiKey is null")
    fun prepareRequest_errorIfApiKeyRequiredButNull() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Test message")
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-4o")
        val provider = TestDefaults.llmProvider1.copy(
            apiKeyId = "required-key-id",
            baseUrl = "https://api.openai.com/v1"
        ) // apiKeyId is NOT null
        val settings = TestDefaults.modelSettings1.copy(temperature = 0.5f)
        val apiKey = null // API key is null

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isLeft(), "Expected error result")
        val error = result.leftOrNull()
        assertIs<LLMCompletionError.ConfigurationError>(error, "Expected ConfigurationError")
        assertTrue(
            error.message.contains("requires an API key"),
            "Error message should indicate missing API key"
        )
    }

    @Test
    @DisplayName("prepareRequest should use flexible JSON string with customParams containing non-standard parameters")
    fun prepareRequest_flexibleJsonStringWithCustomParams() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Test message")
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-4")
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "test-key-id")
        val settings = TestDefaults.modelSettings1.copy(
            temperature = 0.7f,
            maxTokens = 100,
            stream = true,
            customParams = buildJsonObject {
                put("seed", JsonPrimitive(12345))
                put("response_format", buildJsonObject {
                    put("type", JsonPrimitive("json_object"))
                })
                put("logit_bias", buildJsonObject {
                    put("2435", JsonPrimitive(-100))
                })
            }
        )
        val apiKey = "sk-test-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        // Verify the body is now a String (pre-serialized JSON)
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body

        // Parse the JSON string back to verify its contents
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        // Verify standard parameters are present
        assertEquals("gpt-4", requestBodyJson["model"]?.jsonPrimitive?.content)
        assertEquals(true, requestBodyJson["stream"]?.jsonPrimitive?.boolean)
        assertEquals(0.7f, requestBodyJson["temperature"]?.jsonPrimitive?.float)
        assertEquals(100, requestBodyJson["max_tokens"]?.jsonPrimitive?.int)

        // Verify custom parameters from customParams are included
        assertEquals(12345, requestBodyJson["seed"]?.jsonPrimitive?.int)
        assertNotNull(requestBodyJson["response_format"], "Should include response_format from customParams")
        assertNotNull(requestBodyJson["logit_bias"], "Should include logit_bias from customParams")

        // Verify messages array is properly serialized
        val messagesJsonArray = requestBodyJson["messages"]?.jsonArray
        assertNotNull(messagesJsonArray, "Should include messages array")
        assertEquals(2, messagesJsonArray.size, "Should have system message + one user message")
    }

    @Test
    @DisplayName("prepareRequest should override customParams with structured settings")
    fun prepareRequest_structuredSettingsOverrideCustomParams() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Test")
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-3.5-turbo")
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "test-key-id")
        val settings = TestDefaults.modelSettings1.copy(
            temperature = 0.9f, // This should override customParams value
            maxTokens = 200,    // This should override customParams value
            customParams = buildJsonObject {
                put("temperature", JsonPrimitive(0.1f)) // Should be overridden
                put("max_tokens", JsonPrimitive(50))     // Should be overridden
                put("seed", JsonPrimitive(42))           // Should be preserved
            }
        )
        val apiKey = "sk-test-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        // Verify the body is now a String (pre-serialized JSON)
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body

        // Parse the JSON string back to verify its contents
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        // Verify that structured settings override customParams
        assertEquals(
            0.9f, requestBodyJson["temperature"]?.jsonPrimitive?.float,
            "Structured temperature setting should override customParams"
        )
        assertEquals(
            200, requestBodyJson["max_tokens"]?.jsonPrimitive?.int,
            "Structured maxTokens setting should override customParams"
        )

        // Verify that custom parameter not in structured settings is preserved
        assertEquals(
            42, requestBodyJson["seed"]?.jsonPrimitive?.int,
            "Custom seed parameter should be preserved"
        )
    }

    @Test
    @DisplayName("prepareRequest should handle empty customParams gracefully")
    fun prepareRequest_emptyCustomParamsHandledGracefully() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Test")
        )
        val modelConfig = TestDefaults.llmModel1.copy(name = "gpt-4")
        val provider = TestDefaults.llmProvider1.copy(apiKeyId = "test-key-id")
        val settings = TestDefaults.modelSettings1.copy(
            temperature = 0.8f,
            customParams = null // No custom params
        )
        val apiKey = "sk-test-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        // Verify the body is now a String (pre-serialized JSON)
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body

        // Parse the JSON string back to verify its contents
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        // Verify standard parameters are still present
        assertEquals("gpt-4", requestBodyJson["model"]?.jsonPrimitive?.content)
        assertEquals(0.8f, requestBodyJson["temperature"]?.jsonPrimitive?.float)

        // Should not contain any unexpected custom parameters
        assertFalse(requestBodyJson.containsKey("seed"), "Should not contain seed parameter")
        assertFalse(requestBodyJson.containsKey("response_format"), "Should not contain response_format parameter")
    }

    // --- processSuccessResponse Tests ---
    @Test
    @DisplayName("prepareRequest should include stream_options when streaming is enabled")
    fun prepareRequest_withStreamingEnabled_includesStreamOptions() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Hello")
        )
        val modelConfig = LLMModel(
            id = 1L,
            name = "gpt-4o",
            providerId = 1L,
            active = true,
            displayName = "GPT-4o"
        )
        val provider = LLMProvider(
            id = 1L,
            apiKeyId = "test-key-id",
            name = "OpenAI",
            description = "OpenAI Provider",
            baseUrl = "https://api.openai.com",
            type = LLMProviderType.OPENAI
        )
        val settings = ChatModelSettings(
            id = 1L,
            modelId = 1L,
            name = "Test Settings",
            stream = true,
            temperature = 0.7f,
            maxTokens = 100
        )
        val apiKey = "sk-test-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        // Verify the body contains stream_options
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        // Verify stream_options is present
        assertTrue(requestBodyJson.containsKey("stream_options"), "Request should contain stream_options")
        val streamOptions = requestBodyJson["stream_options"]?.jsonObject
        assertNotNull(streamOptions, "stream_options should be a JSON object")
        assertEquals(JsonPrimitive(true), streamOptions["include_usage"], "include_usage should be true")
    }

    @Test
    @DisplayName("prepareRequest should not include stream_options when streaming is disabled")
    fun prepareRequest_withStreamingDisabled_excludesStreamOptions() {
        // Given
        val messages = listOf(
            RawChatMessage.User("Hello")
        )
        val modelConfig = LLMModel(
            id = 1L,
            name = "gpt-4o",
            providerId = 1L,
            active = true,
            displayName = "GPT-4o"
        )
        val provider = LLMProvider(
            id = 1L,
            apiKeyId = "test-key-id",
            name = "OpenAI",
            description = "OpenAI Provider",
            baseUrl = "https://api.openai.com",
            type = LLMProviderType.OPENAI
        )
        val settings = ChatModelSettings(
            id = 1L,
            modelId = 1L,
            name = "Test Settings",
            stream = false,
            temperature = 0.7f,
            maxTokens = 100
        )
        val apiKey = "sk-test-key"

        // When
        val result = strategy.prepareRequest(messages, modelConfig, provider, settings, apiKey, systemMessage = settings.systemMessage)

        // Then
        assertTrue(result.isRight(), "Expected success result")
        val config = result.getOrNull()
        assertNotNull(config, "Expected non-null ApiRequestConfig")

        // Verify the body does not contain stream_options
        assertTrue(config.body is String, "Body should be a pre-serialized JSON String")
        val requestBodyString = config.body
        val requestBodyJson = Json.decodeFromString<JsonObject>(requestBodyString)

        // Verify stream_options is not present
        assertFalse(
            requestBodyJson.containsKey("stream_options"),
            "Request should not contain stream_options when streaming is disabled"
        )
    }
}
