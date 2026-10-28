package eu.torvian.chatbot.worker.builtin.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that terminal decoration adds PTY variables without leaking the worker environment.
 */
class TerminalEnvironmentTest {

    @Test
    fun `preserves base provider values`() {
        val base = mapOf("PATH" to "/usr/bin", "LANG" to "C.UTF-8", "CUSTOM" to "value")
        val result = terminalEnvironment(base)

        assertEquals("/usr/bin", result["PATH"])
        assertEquals("C.UTF-8", result["LANG"])
        assertEquals("value", result["CUSTOM"])
    }

    @Test
    fun `sets and overrides terminal variables`() {
        val base = mapOf("TERM" to "dumb", "PAGER" to "less")
        val result = terminalEnvironment(base)

        assertEquals("xterm-256color", result["TERM"])
        assertEquals("1", result["NO_COLOR"])
        assertEquals("0", result["CLICOLOR"])
        assertEquals("cat", result["PAGER"])
        assertEquals("cat", result["GIT_PAGER"])
        assertEquals("cat", result["SYSTEMD_PAGER"])
        assertEquals("noninteractive", result["DEBIAN_FRONTEND"])
    }

    @Test
    fun `adds prompt variables only when absent`() {
        val withPrompt = terminalEnvironment(mapOf("PS1" to "custom> ", "PROMPT_COMMAND" to "set-title"))
        assertEquals("custom> ", withPrompt["PS1"])
        assertEquals("set-title", withPrompt["PROMPT_COMMAND"])

        val withoutPrompt = terminalEnvironment(emptyMap())
        assertTrue(withoutPrompt.containsKey("PS1"))
        assertEquals("", withoutPrompt["PROMPT_COMMAND"])
    }

    @Test
    fun `does not introduce worker environment variables outside the base map`() {
        val base = mapOf("PATH" to "/usr/bin")
        val result = terminalEnvironment(base)

        // The ambient environment is never a source of child variables, so its keys carry no leak
        // signal: a host that happens to export TERM or PAGER would fail here even though the
        // decorator set those values itself. Only the documented terminal variables may be added.
        val terminalKeys = setOf(
            "TERM",
            "NO_COLOR",
            "CLICOLOR",
            "PAGER",
            "GIT_PAGER",
            "SYSTEMD_PAGER",
            "DEBIAN_FRONTEND",
            "PS1",
            "PROMPT_COMMAND",
        )
        val unexpectedWorkerKeys = result.keys - base.keys - terminalKeys
        assertTrue(
            unexpectedWorkerKeys.isEmpty(),
            "Unexpected worker variables leaked into the child environment: $unexpectedWorkerKeys",
        )
    }

    @Test
    fun `keeps isolated account identity values`() {
        val base = mapOf("HOME" to "/home/agent", "USER" to "agent", "LOGNAME" to "agent")
        val result = terminalEnvironment(base)

        assertEquals("/home/agent", result["HOME"])
        assertEquals("agent", result["USER"])
        assertEquals("agent", result["LOGNAME"])
    }

    @Test
    fun `does not mutate the base map`() {
        val base = mapOf("PATH" to "/usr/bin")
        terminalEnvironment(base)

        assertEquals(mapOf("PATH" to "/usr/bin"), base)
        assertFalse(base.containsKey("TERM"))
    }
}
