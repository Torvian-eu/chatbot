package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies llmapiclientktor openrouter transport tests at the HTTP client boundary.
 */
@DisplayName("LLMApiClientKtor OpenRouter transport tests")
class LLMApiClientKtorOpenRouterTest : LLMApiClientKtorTestBase() {

    @Test
    fun `completeChat should forward OpenRouter attribution headers`() = runTest {
        val openRouterProvider = testProvider.copy(
            type = LLMProviderType.OPENROUTER,
            name = "OpenRouter",
            apiKeyId = "openrouter-key",
            baseUrl = "https://openrouter.ai/api/v1"
        )
        val strategy = OpenAIChatStrategy(Json { ignoreUnknownKeys = true })
        val responseBody = """
            {"id":"completion-123","object":"chat.completion","created":1678885370,
             "model":"openai/gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
        """.trimIndent()

        httpClient = createMockHttpClient { request ->
            assertEquals("Bearer $testApiKey", request.headers[HttpHeaders.Authorization])
            assertEquals("https://chatbot.torvian.eu", request.headers["HTTP-Referer"])
            assertEquals("Torvian Chatbot", request.headers["X-OpenRouter-Title"])
            assertEquals("cloud-agent,general-chat", request.headers["X-OpenRouter-Categories"])
            respond(
                content = ByteReadChannel(responseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, mapOf(LLMProviderType.OPENROUTER to strategy))

        val result = client.completeChat(
            testMessages,
            testModel,
            openRouterProvider,
            testSettings,
            testApiKey
        )

        assertTrue(result.isRight(), "Result should be successful")
    }
}
