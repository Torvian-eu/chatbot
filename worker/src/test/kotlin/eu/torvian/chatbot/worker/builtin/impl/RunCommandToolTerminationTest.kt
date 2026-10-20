package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.builtin.command.CommandTerminator
import eu.torvian.chatbot.worker.builtin.command.InheritChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.TrackedCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that the tool delegates termination of a started command to its terminator.
 *
 * An injected terminator records the delegation and performs the only teardown these tests need, so
 * the tool's cleanup paths are asserted without depending on how a real strategy signals a tree.
 */
class RunCommandToolTerminationTest {

    /**
     * Records every delegation and force-terminates the tracked root.
     *
     * @property terminateCalls Number of termination requests received.
     * @property trackCalls Number of tracking requests received.
     */
    private class RecordingTerminator : CommandTerminator {
        /** Terminations requested by the tool. */
        val terminateCalls = AtomicInteger()

        /** Tracking refreshes requested by the tool. */
        val trackCalls = AtomicInteger()

        /** Process ids for which termination was requested. */
        val terminatedPids = mutableListOf<Long>()

        override fun track(command: TrackedCommand) {
            trackCalls.incrementAndGet()
        }

        override fun terminate(command: TrackedCommand, deadline: Long) {
            terminateCalls.incrementAndGet()
            terminatedPids += command.root.pid()
            // Without this the reader threads stay blocked on a live child's pipes.
            command.root.destroyForcibly()
            runCatching { command.root.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    /** Verifies that a timeout cleans up through the terminator once and still reports the timeout. */
    @Test
    fun `timeout terminates the started command exactly once`() = runTest {
        val terminator = RecordingTerminator()
        val dir = createTempDirectory("run-command-termination-timeout")
        try {
            val (command, args) = RunCommandTestSupport.hangCommand()
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool(terminator),
                RunCommandTestSupport.buildInput(command, args, timeout = 1),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.TIMEOUT)
            assertEquals(1, terminator.terminateCalls.get(), "Cleanup must delegate exactly once")
            assertTrue(terminator.trackCalls.get() >= 1, "The started command was never tracked")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that cancellation terminates the command once and rethrows the cancellation. */
    @Test
    fun `cancellation terminates the started command and is rethrown`() = runTest {
        val terminator = RecordingTerminator()
        val dir = createTempDirectory("run-command-termination-cancel")
        var running: Deferred<*>? = null
        try {
            val (command, args) = RunCommandTestSupport.hangCommand()
            val deferred = async(Dispatchers.Default) {
                tool(terminator).execute(
                    RunCommandTestSupport.buildInput(command, args, timeout = 30),
                    RunCommandTestSupport.context(dir),
                )
            }
            running = deferred
            // Cancel only once the tool has started and tracked the command, so the cleanup path is
            // reached with a live process rather than with a not-yet-started command.
            val startedBy = System.nanoTime() + 5_000_000_000L
            while (terminator.trackCalls.get() == 0 && System.nanoTime() < startedBy) {
                Thread.sleep(10)
            }
            assertTrue(terminator.trackCalls.get() > 0, "The command was never started")

            deferred.cancel(CancellationException("test cancellation"))

            assertFailsWith<CancellationException> { deferred.await() }
            assertEquals(1, terminator.terminateCalls.get(), "Cancellation must delegate exactly once")
        } finally {
            running?.cancelAndJoin()
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that a successful command never reaches the termination strategy. */
    @Test
    fun `success path never terminates anything`() = runTest {
        val terminator = RecordingTerminator()
        val dir = createTempDirectory("run-command-termination-success")
        try {
            val (command, args) = RunCommandTestSupport.echoCommand("termination-success")
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool(terminator),
                RunCommandTestSupport.buildInput(command, args, timeout = 30),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(0, terminator.terminateCalls.get(), "A successful command must not be terminated")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Builds the tool under test with a recording terminator and the unisolated wiring.
     *
     * @param terminator Terminator the tool must delegate to.
     * @return Tool instance under test.
     */
    private fun tool(terminator: CommandTerminator): RunCommandTool {
        val resolution = CommandIsolationResolution.none()
        return RunCommandTool(
            launcher = resolution.launcher,
            childEnvironment = InheritChildEnvironmentProvider(),
            terminator = terminator,
        )
    }
}
