package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenario-driven unit tests for [RunCommandTool].
 *
 * These tests lock down the current intended semantics: successful command execution with exit
 * code 0, non-zero exit codes surfaced as errors, structured `details` payload, and timeout
 * handling. They do not redesign the tool.
 *
 * The worker may run on Windows or Linux (e.g. inside a Docker container), so the commands are
 * built in an OS-aware way: Windows invokes the system shell (`cmd /c ...`) because `echo`/`exit`
 * are shell builtins rather than standalone executables, while Linux invokes `echo`/`sh` directly.
 */
class RunCommandToolTest {

    private val tool = RunCommandTool()

    /** Whether the test host is Windows; used to pick OS-appropriate commands. */
    private val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

    /**
     * Builds a `(command, args)` pair for an `echo` of [text] that works on the current OS.
     */
    private fun echoCommand(text: String): Pair<String, List<String>> =
        if (isWindows) "cmd" to listOf("/c", "echo", text) else "echo" to listOf(text)

    /**
     * Builds a `(command, args)` pair that exits with code [code] on the current OS.
     */
    private fun exitCommand(code: Int): Pair<String, List<String>> =
        if (isWindows) "cmd" to listOf("/c", "exit", code.toString()) else "sh" to listOf("-c", "exit $code")

    /**
     * Builds a `(command, args)` pair that blocks indefinitely so the timeout path is exercised.
     *
     * On Windows an infinite `cmd` `for` loop is used (its `conhost` child survives `destroyForcibly()`
     * only for `choice`/`timeout`, not for a plain `cmd` loop). On Linux a long `sleep` is used, which
     * `destroyForcibly()` terminates reliably.
     */
    private fun hangCommand(): Pair<String, List<String>> =
        if (isWindows) {
            "cmd" to listOf("/c", "for", "/l", "%i", "in", "(1,1,2147483647)", "do", "@(echo", "%i", ">nul)")
        } else {
            "sleep" to listOf("30")
        }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Builds the JSON input object accepted by [RunCommandTool.execute].
     *
     * @param command Command to execute.
     * @param args Optional command arguments.
     * @param timeout Optional timeout in seconds.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(command: String, args: List<String>? = null, timeout: Long? = null): JsonObject =
        buildJsonObject {
            put("command", command)
            if (args != null) putJsonArray("args") {
                for (a in args) add(a)
            }
            if (timeout != null) put("timeout", timeout)
        }

    /**
     * Creates an execution context rooted at [workspace] using the IO dispatcher.
     */
    private fun context(workspace: Path): BuiltInToolExecutionContext =
        BuiltInToolExecutionContext(
            workspace = workspace,
            defaultCommandTimeoutSeconds = 60,
            ioDispatcher = Dispatchers.IO,
        )

    /**
     * Asserts that [result] is a successful (non-error) result and returns its textual output.
     */
    private fun assertSuccess(result: BuiltInToolExecutionResult): String {
        assertTrue(!result.isError, "Expected success but got error: ${result.errorMessage}")
        return result.output ?: ""
    }

    /**
     * Asserts that [result] is an error result carrying the expected [code].
     */
    private fun assertError(result: BuiltInToolExecutionResult, code: String) {
        assertTrue(result.isError, "Expected error but got success: ${result.output}")
        assertEquals(code, result.errorCode, "Unexpected error code; message=${result.errorMessage}")
    }

    // -----------------------------------------------------------------------------------------
    // Scenarios
    // -----------------------------------------------------------------------------------------

    @Test
    fun `executing a basic echoing command returns exit code 0 and output`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // `echo` is a shell builtin on Windows, so invoke it through the system shell there.
            val (command, args) = echoCommand("hello")
            val result = tool.execute(buildInput(command, args), context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("exitCode: 0"), "output should report exit code 0; got:\n$output")
            assertTrue(output.contains("hello"), "output should contain echoed text; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `non-zero exit code returns error with exit code and execution failed`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (command, args) = exitCommand(3)
            val result = tool.execute(buildInput(command, args), context(dir))

            assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
            assertEquals(
                result.errorMessage?.contains("3"),
                true,
                "error message should mention exit code; got: ${result.errorMessage}"
            )
            val output = result.output ?: ""
            assertTrue(output.contains("exitCode: 3"), "output should report exit code 3; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `details payload contains structured stdout stderr exitCode and timeoutSeconds`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (command, args) = echoCommand("hi")
            val result = tool.execute(buildInput(command, args, timeout = 30), context(dir))

            assertSuccess(result)
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
    @Test
    fun `single string args are treated as a single argument and execute successfully`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Passing args as a single JsonPrimitive string instead of a JsonArray.
            val input = buildJsonObject {
                put("command", echoCommand("hello").first)
                put("args", "hello")
            }
            val result = tool.execute(input, context(dir))

            val output = assertSuccess(result)
            assertTrue(output.contains("hello"), "output should contain echoed text; got:\n$output")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `command exceeding the timeout is destroyed and returns timeout`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // The hang command blocks indefinitely; `destroyForcibly()` terminates it so the 1s timeout
            // is enforced. (On Windows avoid `choice`/`timeout`, whose conhost child survives
            // `destroyForcibly()` and would hang the test.)
            val (command, args) = hangCommand()
            val result = tool.execute(buildInput(command, args, timeout = 1), context(dir))

            assertError(result, BuiltInToolExecutionError.TIMEOUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
