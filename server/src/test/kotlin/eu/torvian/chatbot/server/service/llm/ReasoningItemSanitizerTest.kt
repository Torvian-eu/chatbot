package eu.torvian.chatbot.server.service.llm

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [sanitizeReasoningItems]/[sanitizeReasoningItem] reduce raw provider reasoning output items
 * to the Responses API `input` shape, dropping the output-only fields OpenAI rejects as unknown
 * parameters.
 */
class ReasoningItemSanitizerTest {

    @Test
    fun `strips output-only fields while preserving encrypted content`() {
        val item = buildJsonObject {
            put("id", "rs_tmp_1")
            put("type", "reasoning")
            put("status", "completed")
            put("format", "unknown")
            put("encrypted_content", "opaque")
        }

        val sanitized = sanitizeReasoningItem(item)

        assertEquals("reasoning", sanitized["type"]?.jsonPrimitive?.content)
        assertEquals("rs_tmp_1", sanitized["id"]?.jsonPrimitive?.content)
        assertNull(sanitized["status"])
        assertNull(sanitized["format"])
        // Encrypted (opaque) reasoning must survive the round-trip for stateless replay.
        assertEquals("opaque", sanitized["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `preserves a null encrypted_content value`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("status", "completed")
            put("encrypted_content", null)
        }

        val sanitized = sanitizeReasoningItem(item)

        assertEquals("reasoning", sanitized["type"]?.jsonPrimitive?.content)
        assertNull(sanitized["status"])
        assertTrue(sanitized.containsKey("encrypted_content"), "encrypted_content key must be preserved even when null")
        assertTrue(sanitized["encrypted_content"] is JsonNull)
    }

    @Test
    fun `preserves summary and content parts while stripping unknown nested fields`() {
        val item = buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_1")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "summary_text")
                    put("text", "A summary.")
                    put("extra", "nested")
                })
            })
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "Chain of thought.")
                })
            })
        }

        val sanitized = sanitizeReasoningItem(item)

        val summaryPart = sanitized["summary"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("summary_text", summaryPart?.get("type")?.jsonPrimitive?.content)
        assertEquals("A summary.", summaryPart?.get("text")?.jsonPrimitive?.content)
        assertNull(summaryPart?.get("extra"))

        val contentPart = sanitized["content"]?.jsonArray?.get(0)?.jsonObject
        assertEquals("reasoning_text", contentPart?.get("type")?.jsonPrimitive?.content)
        assertEquals("Chain of thought.", contentPart?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `sanitizes a realistic OpenRouter reasoning item like the one persisted in the database`() {
        val item = buildJsonObject {
            put("id", "rs_tmp_6er3482x13w")
            put("type", "reasoning")
            put("status", "completed")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning_text")
                    put("text", "The user wants me to test the search_files tool.")
                })
            })
            put("summary", buildJsonArray { })
            put("format", "unknown")
        }

        val sanitized = sanitizeReasoningItem(item)

        assertEquals("reasoning", sanitized["type"]?.jsonPrimitive?.content)
        assertEquals("rs_tmp_6er3482x13w", sanitized["id"]?.jsonPrimitive?.content)
        assertNull(sanitized["status"], "status is an output-only field and must be stripped")
        assertNull(sanitized["format"], "format is provider-specific and must be stripped")
        assertEquals("reasoning_text", sanitized["content"]?.jsonArray?.get(0)?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("The user wants me to test the search_files tool.", sanitized["content"]?.jsonArray?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.content)
        assertTrue(sanitized["summary"]?.jsonArray?.isEmpty() == true)
    }

    @Test
    fun `null and empty input lists are passed through unchanged`() {
        assertNull(sanitizeReasoningItems(null))
        assertTrue(sanitizeReasoningItems(emptyList()).isNullOrEmpty())
    }
}
