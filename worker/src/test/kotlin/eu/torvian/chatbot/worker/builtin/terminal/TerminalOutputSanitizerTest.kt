package eu.torvian.chatbot.worker.builtin.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies ANSI/control-sequence stripping, normalization and TUI detection.
 */
class TerminalOutputSanitizerTest {

    private fun sanitize(stream: String, threshold: Double = 0.15): String {
        val chars = stream.toCharArray()
        return TerminalOutputSanitizer(threshold).feed(chars, chars.size)
    }

    @Test
    fun `removes complete csi color sequences`() {
        assertEquals("red", sanitize("\u001b[31mred\u001b[0m"))
    }

    @Test
    fun `removes osc title sequences terminated by bel and st`() {
        assertEquals("ab", sanitize("\u001b]0;title\u0007a\u001b]2;other\u001b\\b"))
    }

    @Test
    fun `removes dcs pm and apc strings`() {
        assertEquals("x", sanitize("\u001bPpayload\u001b\\x"))
        assertEquals("x", sanitize("\u001b^payload\u001b\\x"))
        assertEquals("x", sanitize("\u001b_payload\u001b\\x"))
    }

    @Test
    fun `removes charset selection escapes`() {
        assertEquals("hi", sanitize("\u001b(Bhi"))
    }

    @Test
    fun `drops c0 controls and del but keeps newline and tab`() {
        assertEquals("ab\n\t", sanitize("a\u0008b\u0007\u0000\u000b\u000c\u007f\n\t"))
    }

    @Test
    fun `normalizes carriage returns`() {
        assertEquals("a\nb\nc", sanitize("a\r\nb\rc"))
        assertEquals("a\n", sanitize("a\r"))
    }

    @Test
    fun `chunk splits do not change the output`() {
        val stream = "start\r\n\u001b[31mred\u001b[0m\t\u001b]0;t\u0007end\u001b(B!"
        val expected = sanitize(stream)
        for (split in 0..stream.length) {
            val sanitizer = TerminalOutputSanitizer(0.15)
            val left = stream.substring(0, split).toCharArray()
            val right = stream.substring(split).toCharArray()
            val actual = sanitizer.feed(left, left.size) + sanitizer.feed(right, right.size)
            assertEquals(expected, actual, "split at $split changed the output")
        }
    }

    @Test
    fun `unterminated csi is capped and text resumes`() {
        val stream = "\u001b[" + "1;".repeat(5000) + "text"
        assertTrue(sanitize(stream).contains("text"))
    }

    @Test
    fun `alternate screen enter sets tui detection and is removed`() {
        val sanitizer = TerminalOutputSanitizer(0.15)
        val stream = "\u001b[?1049hhello"
        val chars = stream.toCharArray()
        assertEquals("hello", sanitizer.feed(chars, chars.size))
        assertTrue(sanitizer.tuiDetected)
    }

    @Test
    fun `full-screen repaint density sets tui detection`() {
        val sanitizer = TerminalOutputSanitizer(0.15)
        val chars = FULL_SCREEN_REPAINT.toCharArray()
        sanitizer.feed(chars, chars.size)
        assertTrue(sanitizer.tuiDetected)
    }

    @Test
    fun `interactive shell startup does not set tui detection`() {
        val sanitizer = TerminalOutputSanitizer(0.15)
        val chars = INTERACTIVE_SHELL_STREAM.toCharArray()
        assertEquals(INTERACTIVE_SHELL_SANITIZED, sanitizer.feed(chars, chars.size))
        assertFalse(sanitizer.tuiDetected, "a plain interactive shell must not look like a full-screen UI")
    }

    @Test
    fun `interactive shell startup is chunk independent`() {
        val stream = INTERACTIVE_SHELL_STREAM
        for (split in 0..stream.length) {
            val sanitizer = TerminalOutputSanitizer(0.15)
            val left = stream.substring(0, split).toCharArray()
            val right = stream.substring(split).toCharArray()
            val actual = sanitizer.feed(left, left.size) + sanitizer.feed(right, right.size)
            assertEquals(INTERACTIVE_SHELL_SANITIZED, actual, "split at $split changed the output")
            assertFalse(sanitizer.tuiDetected, "split at $split changed tui detection")
        }
    }

    @Test
    fun `plain text does not set tui detection`() {
        val sanitizer = TerminalOutputSanitizer(0.15)
        val stream = "plain line-oriented text\n".repeat(64)
        val chars = stream.toCharArray()
        sanitizer.feed(chars, chars.size)
        assertFalse(sanitizer.tuiDetected)
    }

    @Test
    fun `surrogate pairs are preserved`() {
        assertEquals("a😀b", sanitize("a😀b"))
    }

    @Test
    fun `sgr colour alone does not set tui detection`() {
        // Colour is decoration on linear text, so even a colour-saturated line stays undetected.
        val sanitizer = TerminalOutputSanitizer(0.15)
        val stream = "\u001b[31mred\u001b[0m ".repeat(64)
        val chars = stream.toCharArray()
        sanitizer.feed(chars, chars.size)
        assertFalse(sanitizer.tuiDetected)
    }

    private companion object {
        /**
         * Prompt rendered by the deployment default `PS1` (`\u@\h:\w\$ `).
         *
         * The hostname and working directory match the reported session, so the prompt is 35
         * printable characters long, the size that made the density heuristic misfire.
         */
        const val PROMPT = "agent@47779446ae76:/app/workspace$ "

        /**
         * Sequence readline emits when it takes over the terminal for a new prompt.
         *
         * `ESC [ ? 1 h` and `ESC =` enable application-cursor-key and keypad-transmit mode;
         * `ESC [ ? 2004 h` enables bracketed paste. None of them indicates a full-screen program.
         */
        const val PROMPT_SETUP = "\u001b[?1h\u001b=\u001b[?2004h"

        /**
         * Sequences readline emits when it hands the accepted line back to the shell.
         *
         * They undo [PROMPT_SETUP]; they are the mirror image of the prompt setup.
         */
        const val LINE_ACCEPTED = "\u001b[?1l\u001b>\u001b[?2004l"

        /**
         * Raw PTY bytes of a plain interactive bash session: startup, `cd /tmp`, `pwd`, `close`.
         *
         * Layout: prompt, readline setup, echoed input, readline teardown, `\r\n`, command output.
         * An OSC title string stands in for a shell-integration hook that sets the window title.
         */
        val INTERACTIVE_SHELL_STREAM: String = buildString {
            append(PROMPT)
            append(PROMPT_SETUP)
            append("\u001b]0;agent@47779446ae76: /app/workspace\u0007")
            append("cd /tmp")
            append(LINE_ACCEPTED)
            append("\r\n")
            append(PROMPT)
            append(PROMPT_SETUP)
            append("pwd")
            append(LINE_ACCEPTED)
            append("\r\n")
            append("/tmp\r\n")
            append(PROMPT)
            append(PROMPT_SETUP)
        }

        /** Printable text expected after the shell stream is sanitized. */
        val INTERACTIVE_SHELL_SANITIZED: String = buildString {
            append(PROMPT)
            append("cd /tmp\n")
            append(PROMPT)
            append("pwd\n")
            append("/tmp\n")
            append(PROMPT)
        }

        /**
         * Bytes a screen-oriented program such as `vim` or `less` writes while repainting.
         *
         * Each row is cursor addressing (`ESC [ row ; col H`), line erase (`ESC [ 2 K`), a text
         * cell and erase-to-end-of-line (`ESC [ K`): the control-heavy shape the heuristic exists
         * to catch, without relying on the alternate screen.
         */
        val FULL_SCREEN_REPAINT: String = buildString {
            for (row in 1..24) {
                append("\u001b[").append(row).append(";1H\u001b[2K~\u001b[K")
                if (row < 24) append("\r\n")
            }
        }
    }
}
