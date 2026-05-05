package eu.torvian.chatbot.server.service.llm.discovery

import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryError
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIModelDiscoveryStrategyTest {

    private val strategy = OpenAIModelDiscoveryStrategy(
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    )

    private val provider = LLMProvider(
        id = 1,
        apiKeyId = "openai-key",
        name = "OpenAI",
        description = "OpenAI provider",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    @Test
    fun `prepareRequest should return GET models endpoint`() {
        val result = strategy.prepareRequest(provider, "secret")

        assertTrue(result.isRight())
        val config = result.getOrNull() ?: error("Expected request config")
        assertEquals("/models", config.path)
        assertEquals(GenericHttpMethod.GET, config.method)
        assertEquals("Bearer secret", config.customHeaders["Authorization"])
    }

    @Test
    fun `processSuccessResponse should map model list`() {
        val payload = """
            {
              "object": "list",
              "data": [
                {"id": "gpt-4o", "object": "model", "created": 123, "owned_by": "openai"},
                {"id": "gpt-4.1", "object": "model", "created": 456, "owned_by": "openai"}
              ]
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(payload)

        assertTrue(result.isRight())
        val models = result.getOrNull()?.models ?: error("Expected discovery models")
        assertEquals(2, models.size)
        assertEquals("gpt-4o", models[0].id)
        assertEquals("openai", models[0].metadata["owned_by"])
    }

    @Test
    fun `prepareRequest should fail when key is required but missing`() {
        val result = strategy.prepareRequest(provider, null)

        assertTrue(result.isLeft())
        assertIs<ModelDiscoveryError.ConfigurationError>(result.leftOrNull())
    }
}

