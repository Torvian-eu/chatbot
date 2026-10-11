package eu.torvian.chatbot.server.service.llm

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the masking and assembly rules of the raw-request payload mapper, including the two
 * credential-masking branches and the header set the payload exposes.
 */
class RawLlmRequestPayloadMapperTest {

    private val baseUrl = "https://api.example.com/v1"

    /** Builds a request configuration whose only credential lives in the authorization header. */
    private fun config(
        path: String = "/chat/completions",
        body: Any = """{"model":"gpt-4"}""",
        customHeaders: Map<String, String> = emptyMap()
    ) = ApiRequestConfig(
        path = path,
        method = GenericHttpMethod.POST,
        body = body,
        contentType = GenericContentType.APPLICATION_JSON,
        customHeaders = customHeaders
    )

    @Test
    fun `payload carries the dispatch url, verb, content type and strategy headers`() {
        val payload = config(
            customHeaders = mapOf("X-Title" to "Chatbot")
        ).toRawLlmRequestPayload(baseUrl, "sk-1234567890")

        assertEquals("POST", payload.method)
        assertEquals("$baseUrl/chat/completions", payload.url)
        assertEquals("Chatbot", payload.headers["X-Title"])
        assertEquals("application/json", payload.headers[HttpHeaders.ContentType])
        assertEquals("""{"model":"gpt-4"}""", payload.body)
    }

    @Test
    fun `a key longer than eight characters exposes at most its first eight characters`() {
        val key = "sk-abcdefghijklmnopq"

        val payload = config(
            customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $key")
        ).toRawLlmRequestPayload(baseUrl, key)

        assertEquals("Bearer sk-abcde...", payload.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a sixteen character key exposes at most half of itself`() {
        val key = "abcdefghijklmnop"

        val payload = config(
            customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $key")
        ).toRawLlmRequestPayload(baseUrl, key)

        assertEquals("Bearer abcdefgh...", payload.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a ten character key exposes at most half of itself`() {
        val key = "abcdefghij"

        val payload = config(
            customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $key")
        ).toRawLlmRequestPayload(baseUrl, key)

        assertEquals("Bearer abcde...", payload.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a key of exactly eight characters is replaced entirely`() {
        val key = "abcdefgh"

        val payload = config(
            customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $key")
        ).toRawLlmRequestPayload(baseUrl, key)

        assertEquals("Bearer ****", payload.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a key shorter than eight characters is replaced entirely`() {
        val key = "short"

        val payload = config(
            customHeaders = mapOf(HttpHeaders.Authorization to "Bearer $key")
        ).toRawLlmRequestPayload(baseUrl, key)

        assertEquals("Bearer ****", payload.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a keyless provider keeps its headers untouched and gains no authorization header`() {
        val payload = config(
            customHeaders = mapOf("X-Title" to "Chatbot")
        ).toRawLlmRequestPayload(baseUrl, null)

        assertEquals(mapOf("X-Title" to "Chatbot", HttpHeaders.ContentType to "application/json"), payload.headers)
    }

    @Test
    fun `a key appearing in the url is masked as well`() {
        val key = "sk-abcdefghijklmnopq"

        val payload = config(path = "/chat/completions?api_key=$key")
            .toRawLlmRequestPayload(baseUrl, key)

        assertEquals("$baseUrl/chat/completions?api_key=sk-abcde...", payload.url)
    }

    @Test
    fun `the body is passed through unchanged`() {
        val body = """{"messages":[{"role":"user","content":"hi"}]}"""

        val payload = config(body = body).toRawLlmRequestPayload(baseUrl, "sk-1234567890")

        assertEquals(body, payload.body)
    }
}
