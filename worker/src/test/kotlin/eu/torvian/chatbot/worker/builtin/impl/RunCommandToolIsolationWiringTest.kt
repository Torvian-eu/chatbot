package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.command.AllowlistChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.CommandLauncher
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.builtin.command.InheritChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.NoopLauncher
import eu.torvian.chatbot.worker.config.CommandEnvironmentConfig
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the tool spawns children through the injected launcher and environment provider.
 *
 * The run-command behaviour suites use an unisolated fixture, so this class covers the wiring the
 * isolation feature adds: the launcher's argv is what actually runs, and only the provider's map
 * reaches the child.
 */
class RunCommandToolIsolationWiringTest {

    /** Replaces the requested argv with a fixed shell command and records what was requested. */
    private class ShellReplacingLauncher : CommandLauncher {
        /** Commands passed to [wrap], in call order. */
        val requestedCommands = mutableListOf<String>()

        /** Arguments passed to [wrap], in call order. */
        val requestedArgs = mutableListOf<List<String>>()

        override fun wrap(command: String, args: List<String>): List<String> {
            requestedCommands += command
            requestedArgs += args
            return if (RunCommandTestSupport.isWindows) {
                listOf("cmd", "/c", "echo wrapped-child")
            } else {
                listOf("sh", "-c", "echo wrapped-child")
            }
        }
    }

    /** Verifies that the executable the launcher returns is the one the operating system runs. */
    @Test
    fun `launcher argv is what actually runs`() = runTest {
        val launcher = ShellReplacingLauncher()
        val tool = RunCommandTool(
            launcher = launcher,
            childEnvironment = InheritChildEnvironmentProvider(),
            terminator = CommandIsolationResolution.none().terminator,
        )
        val dir = createTempDirectory("run-command-isolation-wiring")
        try {
            // The requested executable does not exist: only the launcher's argv is executed, so the
            // command still succeeds, which is what proves the wrapping was applied.
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput("definitely-not-a-real-executable", listOf("--flag")),
                RunCommandTestSupport.context(dir),
            )

            val output = RunCommandTestSupport.assertSuccess(result)
            assertTrue(output.contains("wrapped-child"), "Launcher argv was not executed; got:\n$output")
            assertEquals(listOf("definitely-not-a-real-executable"), launcher.requestedCommands)
            assertEquals(listOf(listOf("--flag")), launcher.requestedArgs)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that the child sees only the environment the provider returns. */
    @Test
    fun `child receives only the provider environment`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest

        val provider = AllowlistChildEnvironmentProvider(
            config = CommandEnvironmentConfig(listOf("PATH"), set = emptyMap()),
            isolatedUser = "agent",
            isolatedHome = "/home/agent",
            parent = mapOf(
                "PATH" to System.getenv("PATH").orEmpty(),
                "WORKER_SECRET" to "leaked-secret",
            ),
        )
        val tool = RunCommandTool(
            launcher = NoopLauncher(),
            childEnvironment = provider,
            terminator = CommandIsolationResolution.none().terminator,
        )
        val dir = createTempDirectory("run-command-isolation-wiring")
        try {
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(
                    "sh",
                    listOf(
                        "-c",
                        $$"echo \"HOME=$HOME\"; echo \"USER=$USER\"; echo \"SECRET=${WORKER_SECRET:-absent}\"",
                    ),
                ),
                RunCommandTestSupport.context(dir),
            )

            val output = RunCommandTestSupport.assertSuccess(result)
            assertTrue(output.contains("HOME=/home/agent"), "Child HOME was not derived; got:\n$output")
            assertTrue(output.contains("USER=agent"), "Child identity was not derived; got:\n$output")
            assertTrue(output.contains("SECRET=absent"), "Provider parent leaked into the child; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
