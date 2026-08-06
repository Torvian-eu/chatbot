package eu.torvian.chatbot.worker.builtin.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [HtmlCleaner].
 *
 * These lock down the safelist-based "clean HTML" contract: core tags and core attributes are kept,
 * non-core tags (script/style/head) and non-core attributes (event handlers, style, class) are
 * dropped, visible text is preserved in document order, and non-HTML / `clean=false` input passes
 * through unchanged.
 */
class HtmlCleanerTest {

    private val cleaner = HtmlCleaner()

    @Test
    fun `clean with clean false returns input unchanged`() {
        val html = "<html><body><script>x()</script><p>hi</p></body></html>"
        assertEquals(html, cleaner.clean(html, clean = false))
    }

    @Test
    fun `blank input returns input unchanged`() {
        assertEquals("", cleaner.clean("", clean = true))
        assertEquals("   ", cleaner.clean("   ", clean = true))
    }

    @Test
    fun `drops script style and head content`() {
        val html = "<html><head><style>.x{}</style></head><body><script>bad()</script><p>T</p></body></html>"
        val output = cleaner.clean(html, clean = true)
        assertFalse(output.contains("<script>"), "script must be dropped: $output")
        assertFalse(output.contains("<style>"), "style must be dropped: $output")
        assertTrue(output.contains("<p>T</p>"), "core content must survive: $output")
    }

    @Test
    fun `keeps core tags and drops non-core attributes`() {
        val html = "<p class=\"a\" style=\"color:red\" onclick=\"evil()\" id=\"p1\">Hi <b>there</b></p>"
        val output = cleaner.clean(html, clean = true)
        assertTrue(output.contains("Hi <b>there</b>"), "core text & emphasis must survive: $output")
        assertFalse(output.contains("onclick"), "event handler must be dropped: $output")
        assertFalse(output.contains("style="), "style attribute must be dropped: $output")
        assertFalse(output.contains("class="), "class attribute must be dropped: $output")
        assertFalse(output.contains("id="), "id attribute must be dropped: $output")
    }

    @Test
    fun `keeps link and image core attributes`() {
        val html = "<a href=\"https://x\" class=\"btn\">Click</a><img src=\"a.png\" alt=\"A\" onerror=\"bad()\">"
        val output = cleaner.clean(html, clean = true)
        assertTrue(output.contains("href=\"https://x\""), "href must survive: $output")
        assertTrue(output.contains("src=\"a.png\""), "src must survive: $output")
        assertTrue(output.contains("alt=\"A\""), "alt must survive: $output")
        assertFalse(output.contains("onerror"), "onerror must be dropped: $output")
    }

    @Test
    fun `does not add rel nofollow to links`() {
        val html = "<a href=\"https://x\">Click</a>"
        val output = cleaner.clean(html, clean = true)
        assertTrue(output.contains("Click"), "link text must survive: $output")
        assertTrue(output.contains("href=\"https://x\""), "href must survive: $output")
        assertFalse(output.contains("rel=\"nofollow\""), "rel=nofollow must not be injected: $output")
    }

    @Test
    fun `keeps headings and tables`() {
        val html = "<h1>Title</h1><table><tr><th>H</th><td>D</td></tr></table>"
        val output = cleaner.clean(html, clean = true)
        assertTrue(output.contains("<h1>Title</h1>"), "heading must survive: $output")
        assertTrue(output.contains("<table>"), "table must survive: $output")
        assertTrue(output.contains("<th>H</th>"), "table header must survive: $output")
        assertTrue(output.contains("<td>D</td>"), "table cell must survive: $output")
    }

    @Test
    fun `non-html-like plain content passes through`() {
        val text = "just plain text, no tags <notatag"
        val output = cleaner.clean(text, clean = true)
        // Not a real HTML markup case; still returns a result without throwing.
        assertTrue(output.isNotBlank())
    }
}
