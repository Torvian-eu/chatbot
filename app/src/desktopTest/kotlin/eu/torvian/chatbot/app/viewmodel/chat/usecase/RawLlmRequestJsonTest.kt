package eu.torvian.chatbot.app.viewmodel.chat.usecase

import eu.torvian.chatbot.common.models.api.core.RawLlmRequestPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the shared request formatting used by both the request viewer and the clipboard action, so the
 * text shown and the text copied are produced by the same code.
 */
class RawLlmRequestJsonTest {

    private fun payload(body: String) = RawLlmRequestPayload(
        method = "POST",
        url = "https://api.example.com/v1/chat/completions",
        headers = mapOf("Content-Type" to "application/json", "Authorization" to "Bearer sk-abcdef..."),
        body = body
    )

    /** Verifies a JSON body is indented with two spaces. */
    @Test
    fun `a json body is pretty-printed with two-space indentation`() {
        val formatted = payload("""{"model":"gpt-4","messages":[]}""").prettyBodyJson()

        assertEquals(
            """
            {
              "model": "gpt-4",
              "messages": []
            }
            """.trimIndent(),
            formatted
        )
    }

    /** Verifies a body the provider would not have parsed as JSON is shown as-is rather than failing. */
    @Test
    fun `a non-json body is returned unchanged`() {
        val body = "not json at all"

        assertEquals(body, payload(body).prettyBodyJson())
    }

    /** Verifies the whole-request document carries every field of the captured request. */
    @Test
    fun `the whole-request json contains method url headers and body`() {
        val request = payload("""{"prompt":"hi"}""").requestJson()

        assertTrue("\"method\": \"POST\"" in request, request)
        assertTrue("\"url\": \"https://api.example.com/v1/chat/completions\"" in request, request)
        assertTrue("\"Content-Type\": \"application/json\"" in request, request)
        assertTrue("\"prompt\": \"hi\"" in request, request)
    }

    /** Verifies a non-JSON body is embedded as a plain string so the document stays valid JSON. */
    @Test
    fun `a non-json body is embedded as a string`() {
        val request = payload("raw <xml/>").requestJson()

        assertTrue("\"body\": \"raw <xml/>\"" in request, request)
    }

    /** Verifies the copied document carries the four wire fields and nothing derived from them. */
    @Test
    fun `the whole-request json contains exactly the four wire fields`() {
        val request = payload("""{"prompt":"hi"}""").requestJson()

        assertEquals(
            setOf("method", "url", "headers", "body"),
            Json.parseToJsonElement(request).jsonObject.keys
        )
    }

    /** Verifies the displayed size counts UTF-8 bytes, so a non-ASCII body is not undercounted. */
    @Test
    fun `the body size counts utf-8 bytes rather than characters`() {
        // Five characters, six UTF-8 bytes: the accented character occupies two bytes.
        val request = payload("héllo")

        assertEquals(5, request.body.length)
        assertEquals(6L, request.utf8BodySizeBytes())
    }

    /** Verifies a body smaller than a kilobyte is reported as plain bytes. */
    @Test
    fun `a body under one kilobyte is reported in bytes`() {
        assertEquals("512 B", formatBodySize(512))
    }

    /** Verifies a larger body shows a readable unit and still states the exact byte count. */
    @Test
    fun `a body at or above one kilobyte shows the unit and the exact byte count`() {
        assertEquals("1.2 KB (1229 bytes)", formatBodySize(1229))
        assertEquals("2.0 MB (2097152 bytes)", formatBodySize(2L * 1024 * 1024))
    }
}
