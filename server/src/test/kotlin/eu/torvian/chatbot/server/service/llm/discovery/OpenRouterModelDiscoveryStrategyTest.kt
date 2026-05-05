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

class OpenRouterModelDiscoveryStrategyTest {

    private val strategy = OpenRouterModelDiscoveryStrategy(
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    )

    private val provider = LLMProvider(
        id = 1,
        apiKeyId = "openrouter-key",
        name = "OpenRouter",
        description = "OpenRouter provider",
        baseUrl = "https://openrouter.ai/api/v1",
        type = LLMProviderType.OPENROUTER
    )

    @Test
    fun `prepareRequest should return GET models endpoint with auth header`() {
        val result = strategy.prepareRequest(provider, "secret")

        assertTrue(result.isRight())
        val config = result.getOrNull() ?: error("Expected request config")
        assertEquals("/models", config.path)
        assertEquals(GenericHttpMethod.GET, config.method)
        assertEquals("Bearer secret", config.customHeaders["Authorization"])
    }

    @Test
    fun `prepareRequest should fail when api key is missing`() {
        val result = strategy.prepareRequest(provider, null)

        assertTrue(result.isLeft())
        assertIs<ModelDiscoveryError.ConfigurationError>(result.leftOrNull())
    }

    @Test
    fun `processSuccessResponse should map OpenRouter models without OpenAI-specific fields`() {
        val payload = """
            {
              "data": [
                {
                  "id": "openai/gpt-4o",
                  "canonical_slug": "openai/gpt-4o",
                  "name": "GPT-4o",
                  "created": 1710000000,
                  "description": "General purpose model",
                  "context_length": 128000
                }
              ]
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(payload)

        assertTrue(result.isRight())
        val models = result.getOrNull()?.models ?: error("Expected discovery models")
        assertEquals(1, models.size)
        assertEquals("openai/gpt-4o", models[0].id)
        assertEquals("GPT-4o", models[0].displayName)
        assertEquals("openai/gpt-4o", models[0].metadata["canonical_slug"])
    }
}

