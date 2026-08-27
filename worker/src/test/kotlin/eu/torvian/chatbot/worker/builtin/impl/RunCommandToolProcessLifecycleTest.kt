package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers timeout, cancellation, and descendant-process cleanup semantics.
 *
 * Each test owns its tool fixture and temporary workspace so the class is safe to execute in
 * parallel with the other run-command test classes.
 */
class RunCommandToolProcessLifecycleTest {
    /** Tool instance kept local to this test class to avoid shared mutable fixtures. */
    private val tool = RunCommandTool()

    /**
     * Verifies the run-command behavior described by the scenario name: linux timeout cleanup terminates visible descendant.
     */
    @Test
    fun `linux timeout cleanup terminates visible descendant`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-descendant")
        val pidFile = dir.resolve("child.pid")
        var childPid: Long? = null
        try {
            val script = $$"sleep 30 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; wait"
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput("sh", listOf("-c", script), timeout = 1),
                RunCommandTestSupport.context(dir),
            )
            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.TIMEOUT)

            val deadline = System.nanoTime() + 5_000_000_000L
            while (!pidFile.toFile().exists() && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
            childPid = pidFile.readText().trim().toLongOrNull()
            assertTrue(childPid != null, "child PID was not recorded")
            val recordedPid = childPid
            var alive = true
            while (alive && System.nanoTime() < deadline) {
                // Java Optional interop is nullable in Kotlin; a missing handle means the child exited.
                alive = ProcessHandle.of(recordedPid).map { it.isAlive }.orElse(false) == true
                if (alive) Thread.sleep(25)
            }
            assertFalse(alive, "visible descendant $childPid survived timeout cleanup")
        } finally {
            childPid?.let { pid ->
                ProcessHandle.of(pid).ifPresent { handle ->
                    if (handle.isAlive) handle.destroyForcibly()
                }
            }
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: cancellation cleans up the process and is rethrown.
     */
    @Test
    fun `cancellation cleans up the process and is rethrown`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-cancellation")
        val pidFile = dir.resolve("child.pid")
        var childPid: Long? = null
        var commandJob: Job? = null
        try {
            val script = $$"sleep 30 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; wait"
            val deferred = async(Dispatchers.Default) {
                tool.execute(
                    RunCommandTestSupport.buildInput("sh", listOf("-c", script), timeout = 30),
                    RunCommandTestSupport.context(dir),
                )
            }
            commandJob = deferred
            childPid = RunCommandTestSupport.waitForPid(pidFile)
            deferred.cancel(CancellationException("test cancellation"))
            assertFailsWith<CancellationException> { deferred.await() }
            val recordedPid = childPid
            assertTrue(RunCommandTestSupport.waitForProcessExit(recordedPid), "cancelled descendant survived cleanup")
        } finally {
            commandJob?.cancelAndJoin()
            RunCommandTestSupport.cleanupRecordedProcess(pidFile, childPid)
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: post exit cleanup terminates descendant holding inherited pipe.
     */
    @Test
    fun `post exit cleanup terminates descendant holding inherited pipe`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-post-exit-descendant")
        val pidFile = dir.resolve("child.pid")
        var childPid: Long? = null
        try {
            val script = $$"sleep 30 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; sleep 0.2; exit 0"
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput("sh", listOf("-c", script), timeout = 1),
                RunCommandTestSupport.context(dir),
            )
            childPid = RunCommandTestSupport.waitForPid(pidFile)

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.TIMEOUT)
            val recordedPid = childPid
            assertTrue(RunCommandTestSupport.waitForProcessExit(recordedPid), "post-exit descendant survived cleanup")
        } finally {
            RunCommandTestSupport.cleanupRecordedProcess(pidFile, childPid)
            dir.toFile().deleteRecursively()
        }
    }
}
