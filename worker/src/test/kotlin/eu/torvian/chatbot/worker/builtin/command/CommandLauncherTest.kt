package eu.torvian.chatbot.worker.builtin.command

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the argv produced by each launcher implementation.
 */
class CommandLauncherTest {

    /** Verifies that a launcher without a privilege boundary leaves the requested argv untouched. */
    @Test
    fun `noop launcher executes the requested command directly`() {
        assertEquals(listOf("ls", "-la"), NoopLauncher().wrap("ls", listOf("-la")))
    }

    /** Verifies that the helper launcher prefixes the helper without reordering the request. */
    @Test
    fun `helper launcher prefixes the helper path`() {
        val launcher = SetuidHelperLauncher("/usr/local/bin/run-as-agent")

        assertEquals(
            listOf("/usr/local/bin/run-as-agent", "ls", "-la"),
            launcher.wrap("ls", listOf("-la"))
        )
    }

    /** Verifies that multiple arguments keep their order, which some commands depend on. */
    @Test
    fun `argument order is preserved for several arguments`() {
        val args = listOf("-c", "echo a b c", "--flag", "value")

        assertEquals(listOf("sh") + args, NoopLauncher().wrap("sh", args))
        assertEquals(listOf("/helper", "sh") + args, SetuidHelperLauncher("/helper").wrap("sh", args))
    }

    /** Verifies that a command without arguments still produces a valid, unambiguous argv. */
    @Test
    fun `empty arguments yield the command alone`() {
        assertEquals(listOf("pwd"), NoopLauncher().wrap("pwd", emptyList()))
        assertEquals(listOf("/helper", "pwd"), SetuidHelperLauncher("/helper").wrap("pwd", emptyList()))
    }
}
