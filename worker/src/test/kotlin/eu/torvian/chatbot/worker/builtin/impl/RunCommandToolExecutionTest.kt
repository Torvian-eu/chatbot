package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers ordinary command execution, result composition, timeout, and basic output-limit behavior.
 *
 * Each test owns its tool fixture and temporary workspace so the class is safe to execute in
 * parallel with the other run-command test classes.
 */
class RunCommandToolExecutionTest {
    /** Tool instance kept local to this test class to avoid shared mutable fixtures. */
    private val tool = RunCommandTool()

    /**
     * Verifies the run-command behavior described by the scenario name: executing a basic echoing command returns exit code 0 and output.
     */
    @Test
    fun `executing a basic echoing command returns exit code 0 and output`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // `echo` is a shell builtin on Windows, so invoke it through the system shell there.
            val (command, args) = RunCommandTestSupport.echoCommand("hello")
            val result =
                tool.execute(RunCommandTestSupport.buildInput(command, args), RunCommandTestSupport.context(dir))

            val output = RunCommandTestSupport.assertSuccess(result)
            assertTrue(output.contains("exitCode: 0"), "output should report exit code 0; got:\n$output")
            assertTrue(output.contains("hello"), "output should contain echoed text; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: non-zero exit code returns error with exit code and execution failed.
     */
    @Test
    fun `non-zero exit code returns error with exit code and execution failed`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (command, args) = RunCommandTestSupport.exitCommand(3)
            val result =
                tool.execute(RunCommandTestSupport.buildInput(command, args), RunCommandTestSupport.context(dir))

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
            assertEquals(
                true,
                result.errorMessage?.contains("3"),
                "error message should mention exit code; got: ${result.errorMessage}"
            )
            val output = result.output ?: ""
            assertTrue(output.contains("exitCode: 3"), "output should report exit code 3; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: details payload contains structured stdout stderr exitCode and timeoutSeconds.
     */
    @Test
    fun `details payload contains structured stdout stderr exitCode and timeoutSeconds`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (command, args) = RunCommandTestSupport.echoCommand("hi")
            val result = tool.execute(
                RunCommandTestSupport.buildInput(command, args, timeout = 30),
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertSuccess(result)
            val details = result.details
                ?: throw AssertionError("expected structured details payload")
            // Windows `cmd` terminates lines with CRLF; Linux `echo` uses LF. Normalize for a stable check.
            val stdout = (details["stdout"]?.jsonPrimitive?.content ?: "").replace("\r\n", "\n")
            assertTrue(stdout.endsWith("hi\n"), "stdout should end with the echoed text and a newline; got: $stdout")
            assertEquals("", details["stderr"]?.jsonPrimitive?.content, "stderr mismatch")
            assertEquals(0, details["exitCode"]?.jsonPrimitive?.intOrNull, "exitCode mismatch")
            assertEquals(30, details["timeoutSeconds"]?.jsonPrimitive?.longOrNull, "timeoutSeconds mismatch")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: command exceeding the timeout is destroyed and returns timeout.
     */
    @Test
    fun `command exceeding the timeout is destroyed and returns timeout`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // The hang command blocks indefinitely; `destroyForcibly()` terminates it so the 1s timeout
            // is enforced. (On Windows avoid `choice`/`timeout`, whose conhost child survives
            // `destroyForcibly()` and would hang the test.)
            val (command, args) = RunCommandTestSupport.hangCommand()
            val result = tool.execute(
                RunCommandTestSupport.buildInput(command, args, timeout = 1),
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertError(result, BuiltInToolExecutionError.TIMEOUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: run_command respects maxLines and maxBytes overrides and truncates with notice.
     */
    @Test
    fun `run_command respects maxLines and maxBytes overrides and truncates with notice`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val file = dir.resolve("multiline.txt")
            file.writeText("line1\nline2\nline3\nline4\nline5", Charsets.UTF_8)
            val (cmd, argsList) = if (RunCommandTestSupport.isWindows) {
                "cmd" to listOf("/c", "type", "multiline.txt")
            } else {
                "cat" to listOf("multiline.txt")
            }
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                    put("maxLines", 2)
                },
                RunCommandTestSupport.context(dir)
            )

            val success = RunCommandTestSupport.assertSuccess(result)
            assertTrue(success.contains("[Output truncated:"), "Expected truncation notice; got: $success")
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: run_command joint stdout and stderr output does not exceed maxLines when both produce output.
     */
    @Test
    fun `run_command joint stdout and stderr output does not exceed maxLines when both produce output`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (cmd, argsList) = if (RunCommandTestSupport.isWindows) {
                "cmd" to listOf("/c", "echo out1 & echo err1 >&2 & echo out2 & echo err2 >&2")
            } else {
                "sh" to listOf("-c", "echo 'out1'; echo 'err1' >&2; echo 'out2'; echo 'err2' >&2")
            }
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                    put("maxLines", 3)
                },
                RunCommandTestSupport.context(dir)
            )

            val success = RunCommandTestSupport.assertSuccess(result)
            val lines = success.lines()
            assertTrue(lines.size <= 6, "Output lines should respect maxLines jointly; got ${lines.size}:\n$success")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: run_command with empty stdout and stderr does not report extra lines in truncation notice.
     */
    @Test
    fun `run_command with empty stdout and stderr does not report extra lines in truncation notice`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (cmd, argsList) = if (RunCommandTestSupport.isWindows) {
                "cmd" to listOf("/c", "rem")
            } else {
                "true" to emptyList()
            }
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                },
                RunCommandTestSupport.context(dir)
            )

            val success = RunCommandTestSupport.assertSuccess(result)
            assertFalse(
                success.contains("[Output truncated:"),
                "Empty command output should not be truncated; got: $success"
            )
            assertTrue(success.contains("exitCode: 0"), "Should report exit code 0; got: $success")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies the run-command behavior described by the scenario name: run_command details JSON contains capped stdout and stderr when output is large.
     */
    @Test
    fun `run_command details JSON contains capped stdout and stderr when output is large`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val file = dir.resolve("multiline.txt")
            file.writeText("line1\nline2\nline3\nline4\nline5", Charsets.UTF_8)
            val (cmd, argsList) = if (RunCommandTestSupport.isWindows) {
                "cmd" to listOf("/c", "type", "multiline.txt")
            } else {
                "cat" to listOf("multiline.txt")
            }
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                    put("maxLines", 2)
                },
                RunCommandTestSupport.context(dir)
            )

            RunCommandTestSupport.assertSuccess(result)
            val details = result.details ?: throw AssertionError("expected details")
            val stdout = details["stdout"]?.jsonPrimitive?.content ?: ""
            val stdoutLines = stdout.lines().filter { it.isNotEmpty() }
            assertTrue(stdoutLines.size <= 2, "details stdout should be capped to maxLines; got: $stdout")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
