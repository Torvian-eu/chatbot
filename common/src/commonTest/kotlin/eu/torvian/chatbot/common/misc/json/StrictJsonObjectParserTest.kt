package eu.torvian.chatbot.common.misc.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies strict pre-validation while retaining kotlinx.serialization's JSON semantics.
 */
class StrictJsonObjectParserTest {

    private val json = Json

    /**
     * Ensures ordinary object input is parsed into the expected JSON object.
     */
    @Test
    fun `valid object parses successfully`() {
        val result = parseStrictJsonObject(json, "{\"value\":\"ok\"}")

        assertEquals("ok", result["value"]?.jsonPrimitive?.content)
    }

    /**
     * Ensures JSON's escaped tab representation remains valid string content.
     */
    @Test
    fun `escaped tab is accepted`() {
        val result = parseStrictJsonObject(json, "{\"value\":\"a\\tb\"}")

        assertEquals("a\tb", result["value"]?.jsonPrimitive?.content)
    }

    /**
     * Ensures a literal tab is rejected before the serializer sees the input.
     */
    @Test
    fun `literal tab is rejected`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            parseStrictJsonObject(json, "{\"value\":\"a\tb\"}")
        }

        assertEquals(
            "Unescaped control character U+0009 inside JSON string at index 11",
            exception.message
        )
    }

    /**
     * Ensures literal newlines receive the same protection as literal tabs.
     */
    @Test
    fun `literal newline is rejected`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            parseStrictJsonObject(json, "{\"value\":\"a\nb\"}")
        }

        assertEquals("Unescaped control character U+000a inside JSON string at index 11", exception.message)
    }
}
