package eu.torvian.chatbot.worker.builtin.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [truncateLinesAndBytes] and [formatTruncationNotice].
 */
class TextTruncationTest {

    /**
     * Verifies that empty strings are handled cleanly with 0 lines and 0 bytes.
     */
    @Test
    fun `empty string handles cleanly`() {
        val result = truncateLinesAndBytes("", maxLines = 10, maxBytes = 100)
        assertEquals("", result.text)
        assertEquals(0, result.linesShown)
        assertEquals(0, result.bytesShown)
        assertFalse(result.isTruncated)
    }

    /**
     * Verifies that truncation respects maxLines limits.
     */
    @Test
    fun `truncates by maxLines`() {
        val text = "line1\nline2\nline3\nline4"
        val result = truncateLinesAndBytes(text, maxLines = 2, maxBytes = 1000)
        assertEquals("line1\nline2", result.text)
        assertEquals(2, result.linesShown)
        assertTrue(result.isTruncated)
    }

    /**
     * Verifies that truncation respects maxBytes limits.
     */
    @Test
    fun `truncates by maxBytes`() {
        val text = "abcdefghijklmnopqrstuvwxyz"
        val result = truncateLinesAndBytes(text, maxLines = 10, maxBytes = 5)
        assertEquals("abcde", result.text)
        assertEquals(5, result.bytesShown)
        assertTrue(result.isTruncated)
    }

    /**
     * Verifies formatting of truncation notice with and without extra hints.
     */
    @Test
    fun `formats truncation notice with or without extra hint`() {
        val notice1 = formatTruncationNotice(5, 100)
        assertTrue(notice1.contains("showing first 5 lines / 100 bytes"))
        assertTrue(notice1.contains("Increase 'maxLines'/'maxBytes' to read further."))

        val notice2 = formatTruncationNotice(3, 50, "Use 'range' or")
        assertTrue(notice2.contains("showing first 3 lines / 50 bytes. Use 'range' or"))
    }

    @Test
    fun `formatTruncationNotice does not contain double space when extraHint is null or blank`() {
        val notice1 = formatTruncationNotice(10, 500, null)
        assertFalse(notice1.contains("  "), "Notice should not contain double spaces: $notice1")
        assertTrue(notice1.contains("bytes. Increase"), "Notice should have single space between bytes. and Increase: $notice1")

        val notice2 = formatTruncationNotice(10, 500, "   ")
        assertFalse(notice2.contains("  "), "Notice with blank extraHint should not contain double spaces: $notice2")
        assertTrue(notice2.contains("bytes. Increase"), "Notice with blank extraHint should have single space: $notice2")

        val notice3 = formatTruncationNotice(10, 500, "Use 'range' or")
        assertFalse(notice3.contains("  "), "Notice with extraHint should not contain double spaces: $notice3")
        assertTrue(notice3.contains("bytes. Use 'range' or"), "Notice with extraHint should have proper spacing: $notice3")
    }
}
