package eu.torvian.chatbot.server.service.llm.discovery

import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OllamaModelDiscoveryStrategyTest {

    private val strategy = OllamaModelDiscoveryStrategy(
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    )

    private val provider = LLMProvider(
        id = 1,
        apiKeyId = null,
        name = "Ollama",
        description = "Local Ollama",
        baseUrl = "http://localhost:11434",
        type = LLMProviderType.OLLAMA
    )

    @Test
    fun `prepareRequest should return GET tags endpoint`() {
        val result = strategy.prepareRequest(provider, null)

        assertTrue(result.isRight())
        val config = result.getOrNull() ?: error("Expected request config")
        assertEquals("/api/tags", config.path)
        assertEquals(GenericHttpMethod.GET, config.method)
    }

    @Test
    fun `processSuccessResponse should map Ollama models`() {
        val payload = """
            {
              "models": [
                {
                  "name": "llama3.2:latest",
                  "model": "llama3.2:latest",
                  "modified_at": "2026-03-01T10:00:00Z",
                  "size": 12345,
                  "digest": "sha256:abc"
                }
              ]
            }
        """.trimIndent()

        val result = strategy.processSuccessResponse(payload)

        assertTrue(result.isRight())
        val models = result.getOrNull()?.models ?: error("Expected discovery models")
        assertEquals(1, models.size)
        assertEquals("llama3.2:latest", models[0].id)
        assertEquals("sha256:abc", models[0].metadata["digest"])
    }
}

