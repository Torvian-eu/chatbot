package eu.torvian.chatbot.common.models.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ToolNameSanitizer], verifying that arbitrary tool names are normalized into the LLM-safe
 * character set `a-zA-Z0-9_-` while remaining deterministic and stable.
 */
class ToolNameSanitizerTest {

    private val sanitizer = ToolNameSanitizer()

    @Test
    fun `sanitizes spaces into underscores`() {
        assertEquals("get_weather", sanitizer.sanitize("get /weather"))
    }

    @Test
    fun `sanitizes dots into underscores`() {
        assertEquals("my_tool", sanitizer.sanitize("my.tool"))
    }

    @Test
    fun `collapses consecutive illegal characters into a single replacement`() {
        assertEquals("a_b", sanitizer.sanitize("a  b"))
        assertEquals("get_weather", sanitizer.sanitize("get  /  weather"))
    }

    @Test
    fun `leaves already legal names unchanged`() {
        assertEquals("a--b", sanitizer.sanitize("a--b"))
        assertEquals("read_text_file", sanitizer.sanitize("read_text_file"))
        assertEquals("Project1_tool", sanitizer.sanitize("Project1_tool"))
    }

    @Test
    fun `sanitizes leading and trailing illegal characters`() {
        assertEquals("tool", sanitizer.sanitize(".tool."))
        assertEquals("tool", sanitizer.sanitize(" tool "))
    }

    @Test
    fun `sanitization is deterministic`() {
        val input = "get /weird.name with spaces"
        assertEquals(sanitizer.sanitize(input), sanitizer.sanitize(input))
    }

    @Test
    fun `result always matches the allowed character set`() {
        val inputs = listOf("get /weather", "my.tool", "a b c", "weird#name!", "  spaced  ")
        for (input in inputs) {
            val result = sanitizer.sanitize(input)
            assertTrue(result.matches(Regex("^[a-zA-Z0-9_-]+$")), "'$result' should match ^[a-zA-Z0-9_-]+\$")
        }
    }

    @Test
    fun `blank input is returned unchanged`() {
        assertEquals("", sanitizer.sanitize(""))
        assertEquals("   ", sanitizer.sanitize("   "))
    }
}

