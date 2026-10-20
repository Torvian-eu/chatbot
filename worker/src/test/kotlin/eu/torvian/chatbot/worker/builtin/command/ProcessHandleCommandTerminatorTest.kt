package eu.torvian.chatbot.worker.builtin.command

import eu.torvian.chatbot.worker.builtin.impl.RunCommandTestSupport
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies the handle-based termination strategy used when no privilege drop is active.
 *
 * The strategy is exercised against real child processes, because its purpose is to reach
 * descendants whose handles were observed while the root was still alive.
 */
class ProcessHandleCommandTerminatorTest {

    /** Verifies that a tracked descendant is gone after termination, together with the root. */
    @Test
    fun `terminate removes the root and its observed descendant`() {
        if (RunCommandTestSupport.isWindows) return
        val dir = createTempDirectory("command-terminator")
        val pidFile = dir.resolve("child.pid")
        val script = $$"sleep 300 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; sleep 300"
        val process = ProcessBuilder("sh", "-c", script).start()
        val command = TrackedCommand(process)
        var childPid: Long? = null
        try {
            childPid = RunCommandTestSupport.waitForPid(pidFile)
            ProcessHandleCommandTerminator().track(command)
            assertTrue(command.descendants.isNotEmpty(), "No descendant was observed to terminate")

            ProcessHandleCommandTerminator().terminate(command, System.nanoTime() + 5_000_000_000L)

            assertTrue(
                RunCommandTestSupport.waitForProcessExit(process.pid()),
                "Root survived termination"
            )
            assertTrue(
                RunCommandTestSupport.waitForProcessExit(childPid),
                "Observed descendant $childPid survived termination"
            )
        } finally {
            childPid?.let { pid ->
                ProcessHandle.of(pid).ifPresent { handle -> if (handle.isAlive) handle.destroyForcibly() }
            }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that tracking and terminating an already exited process is a quiet no-op. */
    @Test
    fun `track and terminate tolerate an exited process`() {
        val process = ProcessBuilder(*echoCommand().toTypedArray()).start()
        val command = TrackedCommand(process)
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Test process did not exit")

        val terminator = ProcessHandleCommandTerminator()
        terminator.track(command)
        terminator.terminate(command, System.nanoTime() + 2_000_000_000L)

        assertTrue(process.toHandle().descendants().count() == 0L, "An exited process must have no observable descendants")
    }

    /**
     * Builds a platform-appropriate command that exits immediately.
     *
     * @return The executable and argument list.
     */
    private fun echoCommand(): List<String> =
        if (RunCommandTestSupport.isWindows) listOf("cmd", "/c", "exit 0") else listOf("true")
}
