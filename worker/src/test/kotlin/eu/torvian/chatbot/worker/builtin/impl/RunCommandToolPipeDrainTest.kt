package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.validation.truncateLinesAndBytes
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers large-output draining, bounded retention, EOF handling, and caller-dispatcher independence.
 *
 * Each test owns its tool fixture and temporary workspace so the class is safe to execute in
 * parallel with the other run-command test classes.
 */
class RunCommandToolPipeDrainTest {
    /** Tool instance kept local to this test class to avoid shared mutable fixtures. */
    private val tool = RunCommandTool()

    /**
     * Verifies the run-command behavior described by the scenario name: large stdout drains without deadlock and retains bounded output.
     */
    @Test
    fun `large stdout drains without deadlock and retains bounded output`() = runTest {
        val dir = createTempDirectory("run-command-large-stdout")
        try {
            val (command, args) = RunCommandTestSupport.largeOutputCommand(stderr = false, byteCount = 128 * 1024)
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxLines = 4, maxBytes = 32),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(0, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
            val stdout = RunCommandTestSupport.stringDetail(result, "stdout")
            assertTrue(stdout.startsWith("PREFIX_SENTINEL"), "retained prefix missing: $stdout")
            assertTrue(stdout.toByteArray(Charsets.UTF_8).size <= 32, "stdout exceeded maxBytes")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: large stderr drains and preserves nonzero execution error.
     */
    @Test
    fun `large stderr drains and preserves nonzero execution error`() = runTest {
        val dir = createTempDirectory("run-command-large-stderr")
        try {
            val (command, args) = RunCommandTestSupport.largeOutputCommand(
                stderr = true,
                byteCount = 128 * 1024,
                exitCode = 7
            )
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxLines = 4, maxBytes = 32),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
            assertEquals(true, result.errorMessage?.contains("7"))
            assertEquals(7, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
            val stderr = RunCommandTestSupport.stringDetail(result, "stderr")
            assertTrue(stderr.startsWith("PREFIX_SENTINEL"), "retained stderr prefix missing: $stderr")
            assertTrue(stderr.toByteArray(Charsets.UTF_8).size <= 32, "stderr exceeded maxBytes")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: large stdout and stderr drain from separate pipes.
     */
    @Test
    fun `large stdout and stderr drain from separate pipes`() = runTest {
        val dir = createTempDirectory("run-command-both-streams")
        try {
            val (command, args) = RunCommandTestSupport.bothStreamsLargeOutputCommand(byteCount = 128 * 1024)
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxLines = 4, maxBytes = 32),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertTrue(RunCommandTestSupport.stringDetail(result, "stdout").toByteArray(Charsets.UTF_8).size <= 32)
            assertTrue(RunCommandTestSupport.stringDetail(result, "stderr").toByteArray(Charsets.UTF_8).size <= 32)
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: large output retains prefix and excludes tail sentinel.
     */
    @Test
    fun `large output retains prefix and excludes tail sentinel`() = runTest {
        val dir = createTempDirectory("run-command-sentinel")
        try {
            val (command, args) = RunCommandTestSupport.largeOutputCommand(
                stderr = false,
                byteCount = 192 * 1024,
                prefix = "FIRST_SENTINEL",
                suffix = "LAST_SENTINEL",
            )
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxLines = 10, maxBytes = 48),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
            assertTrue(RunCommandTestSupport.stringDetail(result, "stdout").startsWith("FIRST_SENTINEL"))
            assertFalse(RunCommandTestSupport.stringDetail(result, "stdout").contains("LAST_SENTINEL"))
            assertFalse((result.output ?: "").contains("LAST_SENTINEL"))
            assertTrue(RunCommandTestSupport.stringDetail(result, "stdout").toByteArray(Charsets.UTF_8).size <= 48)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: output above historical pipe threshold completes promptly.
     */
    @Test
    fun `output above historical pipe threshold completes promptly`() = runTest {
        val dir = createTempDirectory("run-command-threshold")
        try {
            val (command, args) = RunCommandTestSupport.largeOutputCommand(stderr = false, byteCount = 96 * 1024)
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxLines = 2, maxBytes = 24),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(0, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: stdin is closed so an eof-reading command can exit.
     */
    @Test
    fun `stdin is closed so an eof-reading command can exit`() = runTest {
        val dir = createTempDirectory("run-command-stdin")
        try {
            val (command, args) = if (RunCommandTestSupport.isWindows) {
                "cmd" to listOf("/c", "more >nul & echo AFTER_EOF")
            } else {
                "sh" to listOf("-c", "cat >/dev/null; printf 'AFTER_EOF\\n'")
            }
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertTrue(RunCommandTestSupport.stringDetail(result, "stdout").contains("AFTER_EOF"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: utf8 output respects byte boundary and existing truncation semantics.
     */
    @Test
    fun `utf8 output respects byte boundary and existing truncation semantics`() = runTest {
        if (RunCommandTestSupport.isWindows) return@runTest
        val dir = createTempDirectory("run-command-utf8")
        try {
            val source = "ééééé\n"
            val file = dir.resolve("utf8.txt")
            file.writeText(source, Charsets.UTF_8)
            // Read a workspace file so the test isolates incremental decoding from shell quoting.
            val (command, args) = "cat" to listOf(file.fileName.toString())
            val maxBytes = 5
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxLines = 2, maxBytes = maxBytes),
                RunCommandTestSupport.context(dir),
            )

            RunCommandTestSupport.assertSuccess(result)
            val actual = RunCommandTestSupport.stringDetail(result, "stdout")
            val expected = truncateLinesAndBytes(source, maxLines = 2, maxBytes = maxBytes).text
            assertEquals(expected, actual)
            assertTrue(actual.toByteArray(Charsets.UTF_8).size <= maxBytes)
            assertEquals("éé", actual, "UTF-8 output should end at a code-point boundary: $actual")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: large output succeeds with a single thread caller dispatcher.
     */
    @Test
    fun `large output succeeds with a single thread caller dispatcher`() = runTest {
        val dir = createTempDirectory("run-command-single-dispatcher")
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val (command, args) = RunCommandTestSupport.largeOutputCommand(stderr = false, byteCount = 96 * 1024)
            val result = RunCommandTestSupport.executeWithTestDeadline(
                tool,
                RunCommandTestSupport.buildInput(command, args, timeout = 5, maxBytes = 32),
                RunCommandTestSupport.context(dir).copy(ioDispatcher = dispatcher),
            )

            RunCommandTestSupport.assertSuccess(result)
            assertEquals(0, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }
}
