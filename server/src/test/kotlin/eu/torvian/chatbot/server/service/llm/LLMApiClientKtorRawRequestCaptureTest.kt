package eu.torvian.chatbot.server.service.llm

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import eu.torvian.chatbot.common.models.core.UsageStats
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the Ktor client publishes the prepared provider request through the per-invocation
 * observer, on both the non-streaming and the streaming path, before the request is dispatched.
 */
class LLMApiClientKtorRawRequestCaptureTest : LLMApiClientKtorTestBase() {

    private val rawKey = "sk-abcdefghijklmnopq"
    private val successBody = """{"choices":[{"index":0,"message":{"role":"assistant","content":"Hi"}}]}"""

    /** Builds the request configuration a strategy would produce for this provider. */
    private fun requestConfig() = ApiRequestConfig(
        path = "/chat/completions",
        method = GenericHttpMethod.POST,
        body = """{"model":"gpt-4"}""",
        contentType = GenericContentType.APPLICATION_JSON,
        customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $rawKey")
    )

    @Test
    fun `completeChat publishes the dispatched request with a masked credential`() = runTest {
        val config = requestConfig()
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, rawKey)
        } returns config.right()
        every { mockStrategy.processSuccessResponse(any()) } returns LLMCompletionResult(
            id = "c1",
            choices = emptyList(),
            usage = UsageStats(1, 1, 2),
            metadata = emptyMap()
        ).right()
        val observed = mutableListOf<RawLlmRequestPayload>()
        val requestReachedEngine = mutableListOf<Boolean>()
        httpClient = createMockHttpClient { _ ->
            requestReachedEngine.add(observed.isNotEmpty())
            respond(
                content = ByteReadChannel(successBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        client.completeChat(
            testMessages, testModel, testProvider, testSettings, rawKey,
            onRequestPrepared = { observed.add(it) }
        )

        assertEquals(1, observed.size)
        val payload = observed.single()
        assertEquals("POST", payload.method)
        assertEquals("${testProvider.baseUrl}/chat/completions", payload.url)
        assertEquals("application/json", payload.headers[HttpHeaders.ContentType])
        assertEquals("Bearer sk-abcde...", payload.headers[HttpHeaders.Authorization])
        assertFalse(payload.headers.values.any { it.contains(rawKey) })
        assertTrue(requestReachedEngine.single(), "The observer must run before the request is dispatched")
    }

    @Test
    fun `completeChat publishes the request even when the provider answers with an error`() = runTest {
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, rawKey)
        } returns requestConfig().right()
        every { mockStrategy.processErrorResponse(any(), any()) } returns
                LLMCompletionError.ApiError(500, "boom", null)
        val observed = mutableListOf<RawLlmRequestPayload>()
        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel("""{"error":"boom"}"""),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        val result = client.completeChat(
            testMessages, testModel, testProvider, testSettings, rawKey,
            onRequestPrepared = { observed.add(it) }
        )

        assertTrue(result.isLeft())
        assertEquals(1, observed.size)
    }

    @Test
    fun `completeChat without an observer behaves as before`() = runTest {
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, rawKey)
        } returns requestConfig().right()
        every { mockStrategy.processSuccessResponse(any()) } returns LLMCompletionResult(
            id = "c1",
            choices = emptyList(),
            usage = UsageStats(1, 1, 2),
            metadata = emptyMap()
        ).right()
        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel(successBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        val result = client.completeChat(testMessages, testModel, testProvider, testSettings, rawKey)

        assertTrue(result.isRight())
    }

    @Test
    fun `a preparation failure publishes nothing`() = runTest {
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, rawKey)
        } returns LLMCompletionError.ConfigurationError("no request").left()
        val observed = mutableListOf<RawLlmRequestPayload>()
        httpClient = createMockHttpClient { _ -> error("The engine must not be reached") }
        client = LLMApiClientKtor(httpClient, strategies)

        val result = client.completeChat(
            testMessages, testModel, testProvider, testSettings, rawKey,
            onRequestPrepared = { observed.add(it) }
        )

        assertTrue(result.isLeft())
        assertTrue(observed.isEmpty())
    }

    @Test
    fun `completeChatStreaming publishes the request before the flow is collected`() = runTest {
        val config = requestConfig()
        every {
            mockStrategy.prepareRequest(testMessages, testModel, testProvider, testSettings, rawKey)
        } returns config.right()
        every { mockStrategy.processStreamingResponse(any()) } returns emptyFlow()
        val observed = mutableListOf<RawLlmRequestPayload>()
        httpClient = createMockHttpClient { _ ->
            respond(
                content = ByteReadChannel("data: [DONE]\n"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        client = LLMApiClientKtor(httpClient, strategies)

        val stream = client.completeChatStreaming(
            testMessages, testModel, testProvider, testSettings, rawKey,
            onRequestPrepared = { observed.add(it) }
        )

        // The capture happens during preparation, so it is already available before collection starts.
        assertEquals(1, observed.size)
        assertEquals("Bearer sk-abcde...", observed.single().headers[HttpHeaders.Authorization])

        stream.collect { }
        assertEquals(1, observed.size)
    }

    @Test
    fun `completeChatStreaming publishes nothing when no strategy resolves`() = runTest {
        val observed = mutableListOf<RawLlmRequestPayload>()
        httpClient = createMockHttpClient { _ -> error("The engine must not be reached") }
        client = LLMApiClientKtor(httpClient, emptyMap())

        val result = client.completeChatStreaming(
            testMessages, testModel, testProvider, testSettings, rawKey,
            onRequestPrepared = { observed.add(it) }
        ).toList()

        assertEquals(1, result.size)
        assertNull(result.single().getOrNull())
        assertTrue(observed.isEmpty())
    }
}
