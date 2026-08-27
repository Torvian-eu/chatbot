package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.validation.truncateLinesAndBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

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
     * @param maxLines Optional maximum number of retained output lines.
     * @param maxBytes Optional maximum number of retained UTF-8 output bytes.
     * @return The input [kotlinx.serialization.json.JsonObject] for the tool.
     */
    private fun buildInput(
        command: String,
        args: List<String>? = null,
        timeout: Long? = null,
        maxLines: Int? = null,
        maxBytes: Int? = null,
    ): JsonObject = buildJsonObject {
        put("command", command)
        if (args != null) putJsonArray("args") {
            for (a in args) add(a)
        }
        if (timeout != null) put("timeout", timeout)
        if (maxLines != null) put("maxLines", maxLines)
        if (maxBytes != null) put("maxBytes", maxBytes)
    }

    /**
     * Creates an execution context rooted at [workspace] using the IO dispatcher.
     */
    private fun context(workspace: Path): BuiltInToolExecutionContext =
        BuiltInToolExecutionContext(
            workspace = workspace,
            defaultCommandTimeoutSeconds = 60,
            defaultSearchTimeoutSeconds = 5,
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

    /**
     * Executes a command under a test-only deadline so a regression cannot hang the test worker.
     *
     * @param input The validated command input passed to the tool.
     * @param executionContext The isolated workspace and execution configuration.
     * @return The result produced by [RunCommandTool.execute].
     */
    private suspend fun executeWithTestDeadline(
        input: JsonObject,
        executionContext: BuiltInToolExecutionContext,
    ): BuiltInToolExecutionResult = withContext(Dispatchers.Default) {
        withTimeout(8_000.milliseconds) {
            tool.execute(input, executionContext)
        }
    }

    /**
     * Builds an OS-aware command that emits a bounded amount of output without relying on a fixed
     * global file or process identifier.
     *
     * @param stderr Whether output should be directed to standard error.
     * @param byteCount Approximate number of filler bytes to emit.
     * @param prefix Distinctive text emitted before the filler.
     * @param suffix Distinctive text emitted after the filler.
     * @param exitCode Exit code returned by the command.
     * @return The executable and argument list for [RunCommandTool].
     */
    private fun largeOutputCommand(
        stderr: Boolean,
        byteCount: Int,
        prefix: String = "PREFIX_SENTINEL",
        suffix: String = "TAIL_SENTINEL",
        exitCode: Int = 0,
    ): Pair<String, List<String>> {
        if (isWindows) {
            // Group the loop so cmd does not treat the suffix or exit command as part of its body.
            val iterations = maxOf(18_000, byteCount / 5 + 1_000)
            val redirection = if (stderr) " 1>&2" else ""
            val script =
                "(echo $prefix & for /l %i in (1,1,$iterations) do @echo X)$redirection & echo $suffix$redirection & exit $exitCode"
            return "cmd" to listOf("/c", script)
        }
        val redirection = if (stderr) " >&2" else ""
        val script = "{ printf '$prefix'; yes X | head -c $byteCount; printf '$suffix'; }$redirection; exit $exitCode"
        return "sh" to listOf("-c", script)
    }

    /**
     * Builds a command that fills both independent output pipes before exiting.
     *
     * @param byteCount Approximate bytes emitted on each stream.
     * @return The executable and argument list for [RunCommandTool].
     */
    private fun bothStreamsLargeOutputCommand(byteCount: Int): Pair<String, List<String>> {
        if (isWindows) {
            // Each grouped loop completes independently while both remain on separate cmd pipes.
            val iterations = maxOf(18_000, byteCount / 5 + 1_000)
            val script =
                "(for /l %i in (1,1,$iterations) do @echo OUT) & (for /l %i in (1,1,$iterations) do @echo ERR 1>&2) & exit 0"
            return "cmd" to listOf("/c", script)
        }
        val script = "{ yes OUT | head -c $byteCount; } & { yes ERR | head -c $byteCount >&2; } & wait"
        return "sh" to listOf("-c", script)
    }

    /**
     * Reads a JSON string detail while giving a useful assertion when the detail is absent.
     *
     * @param result Tool result containing the details object.
     * @param key Name of the string detail to read.
     * @return The detail value, or an empty string when the JSON value is absent.
     */
    private fun stringDetail(result: BuiltInToolExecutionResult, key: String): String =
        result.details?.jsonObject?.get(key)?.jsonPrimitive?.content ?: ""

    /**
     * Builds an input payload where `args` is intentionally a single JSON string,
     * which should be rejected as invalid input.
     *
     * The command must still be valid on each platform: on Windows `cmd` expects `/c <command>`
     * as one command-line string, while Linux can execute `echo` with a plain single argument.
     *
     * @param text Text to echo.
     * @return A [JsonObject] with `command` and string-form `args`.
     */
    private fun singleStringArgsInput(text: String): JsonObject =
        buildJsonObject {
            if (isWindows) {
                put("command", "cmd")
                put("args", "/c echo $text")
            } else {
                put("command", "echo")
                put("args", text)
            }
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
    fun `single string args are rejected with invalid input error and helpful message`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Keep `args` as a JsonPrimitive string which should now be rejected.
            val input = singleStringArgsInput("hello")
            val result = tool.execute(input, context(dir))

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(1, errorsArray.size, "should have 1 validation error")
            val errorText = errorsArray.first().jsonPrimitive.content
            assertTrue(
                errorText.contains("array of strings"),
                "error message should mention array syntax; got: $errorText"
            )
            assertTrue(
                errorText.contains("args"),
                "error message should mention the args field; got: $errorText"
            )
            assertTrue(
                errorText.contains("Use array syntax"),
                "error message should suggest array syntax; got: $errorText"
            )
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

    @Test
    fun `command field containing spaces returns invalid input error with guidance`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Simulate LLM misuse: full command line placed in the `command` field.
            val result = tool.execute(buildInput("echo hello world"), context(dir))

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(1, errorsArray.size, "should have 1 validation error")
            val errorText = errorsArray.first().jsonPrimitive.content
            assertEquals(
                true,
                errorText.contains("args"),
                "error message should mention the 'args' field; got: $errorText"
            )
            assertEquals(
                true,
                errorText.contains("echo hello world"),
                "error message should echo back the received command; got: $errorText"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple validation errors are accumulated instead of failing on first`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Both command has spaces AND timeout is invalid (<= 0).
            val result = tool.execute(buildInput("echo hello world", timeout = 0), context(dir))

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with accumulated validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(2, errorsArray.size, "should have accumulated 2 validation errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(
                errorTexts.any { it.contains("command") && it.contains("spaces") },
                "should contain command whitespace error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("timeout") },
                "should contain timeout error; got: $errorTexts"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed command and args values are rejected as invalid input`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val result = tool.execute(
                buildJsonObject {
                    put("command", buildJsonObject { put("nested", "value") })
                    put("args", buildJsonObject { put("nested", "value") })
                },
                context(dir)
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(2, errorsArray.size, "should have accumulated 2 validation errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(errorTexts.any { it.contains("command") && it.contains("string") })
            assertTrue(errorTexts.any { it.contains("args") && it.contains("array of strings") })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown parameter returns invalid input error`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Build an input with an unknown parameter "unknownParam"
            val result = tool.execute(
                buildJsonObject {
                    put("command", "echo")
                    put("unknownParam", "someValue")
                },
                context(dir)
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(1, errorsArray.size, "should have 1 validation error")
            val errorText = errorsArray.first().jsonPrimitive.content
            assertTrue(
                errorText.contains("unknownParam"),
                "error message should mention the unknown parameter; got: $errorText"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `multiple unknown parameters are accumulated in validation errors`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Build an input with multiple unknown parameters
            val result = tool.execute(
                buildJsonObject {
                    put("command", "echo")
                    put("unknownParam1", "value1")
                    put("unknownParam2", "value2")
                },
                context(dir)
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with accumulated validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(2, errorsArray.size, "should have accumulated 2 unknown parameter errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(
                errorTexts.any { it.contains("unknownParam1") },
                "should contain unknownParam1 error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("unknownParam2") },
                "should contain unknownParam2 error; got: $errorTexts"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown parameter combined with other validation errors are all accumulated`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            // Build an input with unknown parameter, command with spaces, and invalid timeout
            val result = tool.execute(
                buildJsonObject {
                    put("command", "echo hello world")
                    put("timeout", 0)
                    put("unknownParam", "someValue")
                },
                context(dir)
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
            val errorDetailsJson = result.errorDetails
                ?: throw AssertionError("expected errorDetails with accumulated validation errors")
            // Parse the errorDetails string as JSON
            val errorDetails = Json.parseToJsonElement(errorDetailsJson).jsonObject
            val errorsArray = errorDetails["validationErrors"]?.jsonArray
                ?: throw AssertionError("expected validationErrors array in errorDetails")
            assertEquals(3, errorsArray.size, "should have accumulated 3 validation errors")
            val errorTexts = errorsArray.map { it.jsonPrimitive.content }
            assertTrue(
                errorTexts.any { it.contains("unknownParam") },
                "should contain unknownParam error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("spaces") },
                "should contain command whitespace error; got: $errorTexts"
            )
            assertTrue(
                errorTexts.any { it.contains("timeout") },
                "should contain timeout error; got: $errorTexts"
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_command respects maxLines and maxBytes overrides and truncates with notice`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val file = dir.resolve("multiline.txt")
            file.writeText("line1\nline2\nline3\nline4\nline5", Charsets.UTF_8)
            val (cmd, argsList) = if (isWindows) {
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
                context(dir)
            )

            val success = assertSuccess(result)
            assertTrue(success.contains("[Output truncated:"), "Expected truncation notice; got: $success")
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_command with invalid maxLines or maxBytes returns invalid input error`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (cmd, argsList) = echoCommand("hello")
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                    put("maxLines", 0)
                },
                context(dir)
            )

            assertError(result, BuiltInToolExecutionError.INVALID_INPUT)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_command joint stdout and stderr output does not exceed maxLines when both produce output`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (cmd, argsList) = if (isWindows) {
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
                context(dir)
            )

            val success = assertSuccess(result)
            val lines = success.lines()
            assertTrue(lines.size <= 6, "Output lines should respect maxLines jointly; got ${lines.size}:\n$success")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_command with empty stdout and stderr does not report extra lines in truncation notice`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val (cmd, argsList) = if (isWindows) {
                "cmd" to listOf("/c", "rem")
            } else {
                "true" to emptyList()
            }
            val result = tool.execute(
                buildJsonObject {
                    put("command", cmd)
                    putJsonArray("args") { argsList.forEach { add(it) } }
                },
                context(dir)
            )

            val success = assertSuccess(result)
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
     * Verifies that stdout larger than a normal OS pipe is drained while retaining only the request's prefix.
     */
    @Test
    fun `large stdout drains without deadlock and retains bounded output`() = runTest {
        val dir = createTempDirectory("run-command-large-stdout")
        try {
            val (command, args) = largeOutputCommand(stderr = false, byteCount = 128 * 1024)
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2, maxLines = 4, maxBytes = 32),
                context(dir),
            )

            assertSuccess(result)
            assertEquals(0, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
            val stdout = stringDetail(result, "stdout")
            assertTrue(stdout.startsWith("PREFIX_SENTINEL"), "retained prefix missing: $stdout")
            assertTrue(stdout.toByteArray(Charsets.UTF_8).size <= 32, "stdout exceeded maxBytes")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that a large stderr pipe is drained even when the command reports a non-zero exit.
     */
    @Test
    fun `large stderr drains and preserves nonzero execution error`() = runTest {
        val dir = createTempDirectory("run-command-large-stderr")
        try {
            val (command, args) = largeOutputCommand(stderr = true, byteCount = 128 * 1024, exitCode = 7)
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2, maxLines = 4, maxBytes = 32),
                context(dir),
            )

            assertError(result, BuiltInToolExecutionError.EXECUTION_FAILED)
            assertEquals(true, result.errorMessage?.contains("7"))
            assertEquals(7, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
            val stderr = stringDetail(result, "stderr")
            assertTrue(stderr.startsWith("PREFIX_SENTINEL"), "retained stderr prefix missing: $stderr")
            assertTrue(stderr.toByteArray(Charsets.UTF_8).size <= 32, "stderr exceeded maxBytes")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that independent stdout and stderr producers can both exceed pipe capacity.
     */
    @Test
    fun `large stdout and stderr drain from separate pipes`() = runTest {
        val dir = createTempDirectory("run-command-both-streams")
        try {
            val (command, args) = bothStreamsLargeOutputCommand(byteCount = 128 * 1024)
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 5, maxLines = 4, maxBytes = 32),
                context(dir),
            )

            assertSuccess(result)
            assertTrue(stringDetail(result, "stdout").toByteArray(Charsets.UTF_8).size <= 32)
            assertTrue(stringDetail(result, "stderr").toByteArray(Charsets.UTF_8).size <= 32)
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that content after the retained prefix is drained but is not returned in details or output.
     */
    @Test
    fun `large output retains prefix and excludes tail sentinel`() = runTest {
        val dir = createTempDirectory("run-command-sentinel")
        try {
            val (command, args) = largeOutputCommand(
                stderr = false,
                byteCount = 192 * 1024,
                prefix = "FIRST_SENTINEL",
                suffix = "LAST_SENTINEL",
            )
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2, maxLines = 10, maxBytes = 48),
                context(dir),
            )

            assertSuccess(result)
            assertEquals(true, result.details?.jsonObject?.get("truncated")?.jsonPrimitive?.boolean)
            assertTrue(stringDetail(result, "stdout").startsWith("FIRST_SENTINEL"))
            assertFalse(stringDetail(result, "stdout").contains("LAST_SENTINEL"))
            assertFalse((result.output ?: "").contains("LAST_SENTINEL"))
            assertTrue(stringDetail(result, "stdout").toByteArray(Charsets.UTF_8).size <= 48)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that the tool handles output modestly above the historical Linux pipe threshold promptly.
     */
    @Test
    fun `output above historical pipe threshold completes promptly`() = runTest {
        val dir = createTempDirectory("run-command-threshold")
        try {
            val (command, args) = largeOutputCommand(stderr = false, byteCount = 96 * 1024)
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2, maxLines = 2, maxBytes = 24),
                context(dir),
            )

            assertSuccess(result)
            assertEquals(0, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that closing stdin allows a command which reads to EOF to finish normally.
     */
    @Test
    fun `stdin is closed so an eof-reading command can exit`() = runTest {
        val dir = createTempDirectory("run-command-stdin")
        try {
            val (command, args) = if (isWindows) {
                "cmd" to listOf("/c", "more >nul & echo AFTER_EOF")
            } else {
                "sh" to listOf("-c", "cat >/dev/null; printf 'AFTER_EOF\\n'")
            }
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2),
                context(dir),
            )

            assertSuccess(result)
            assertTrue(stringDetail(result, "stdout").contains("AFTER_EOF"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that incremental UTF-8 retention never returns a partial multibyte code point and
     * agrees with the existing truncation helper for valid process output.
     */
    @Test
    fun `utf8 output respects byte boundary and existing truncation semantics`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-utf8")
        try {
            val source = "ééééé\n"
            val file = dir.resolve("utf8.txt")
            file.writeText(source, Charsets.UTF_8)
            // Read a workspace file so the test isolates incremental decoding from shell quoting.
            val (command, args) = "cat" to listOf(file.fileName.toString())
            val maxBytes = 5
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2, maxLines = 2, maxBytes = maxBytes),
                context(dir),
            )

            assertSuccess(result)
            val actual = stringDetail(result, "stdout")
            val expected = truncateLinesAndBytes(source, maxLines = 2, maxBytes = maxBytes).text
            assertEquals(expected, actual)
            assertTrue(actual.toByteArray(Charsets.UTF_8).size <= maxBytes)
            assertEquals("éé", actual, "UTF-8 output should end at a code-point boundary: $actual")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies best-effort termination of a visible Linux descendant when the root command times out.
     */
    @Test
    fun `linux timeout cleanup terminates visible descendant`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-descendant")
        val pidFile = dir.resolve("child.pid")
        var childPid: Long? = null
        try {
            val script = $$"sleep 30 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; wait"
            val result = executeWithTestDeadline(
                buildInput("sh", listOf("-c", script), timeout = 1),
                context(dir),
            )
            assertError(result, BuiltInToolExecutionError.TIMEOUT)

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
     * Waits for a command-written PID file without assuming a fixed process startup duration.
     *
     * @param pidFile File expected to contain a decimal process identifier.
     * @param timeoutMillis Maximum wall-clock time to wait.
     * @return The recorded process identifier.
     */
    private fun waitForPid(pidFile: Path, timeoutMillis: Long = 3_000): Long {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val pid = pidFile.toFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            if (pid != null) return pid
            Thread.sleep(25)
        }
        throw AssertionError("PID was not recorded in $pidFile")
    }

    /**
     * Waits briefly for [pid] to become absent or non-running after command cleanup.
     *
     * Linux PID 1 can retain a killed orphan as a zombie in this test container; that process is
     * terminated and no longer consumes resources even though ProcessHandle still reports it alive.
     *
     * @param pid Process identifier to inspect.
     * @param timeoutMillis Maximum wall-clock time to poll.
     * @return True when the process is no longer running.
     */
    private fun waitForProcessExit(pid: Long, timeoutMillis: Long = 3_000): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val handle = ProcessHandle.of(pid).orElse(null)
            if (handle == null || !handle.isAlive || isLinuxZombie(pid)) return true
            Thread.sleep(25)
        }
        val handle = ProcessHandle.of(pid).orElse(null)
        return handle == null || !handle.isAlive || isLinuxZombie(pid)
    }

    /**
     * Terminates a PID recorded by a test command, including when the assertion failed before the
     * caller copied the PID into its local variable.
     *
     * @param pidFile File containing the command's PID.
     * @param fallbackPid PID already captured by the test, if any.
     */
    private fun cleanupRecordedProcess(pidFile: Path, fallbackPid: Long?) {
        val pid = fallbackPid ?: pidFile.toFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        pid?.let { recordedPid ->
            ProcessHandle.of(recordedPid).ifPresent { handle ->
                if (handle.isAlive) handle.destroyForcibly()
            }
        }
    }

    /**
     * Detects a terminated zombie in Linux's procfs when its parent reaper has not collected it.
     *
     * @param pid Process identifier to inspect.
     * @return True when procfs reports the process state as zombie.
     */
    private fun isLinuxZombie(pid: Long): Boolean {
        if (isWindows) return false
        val stat = Path.of("/proc", pid.toString(), "stat").toFile()
        val contents = stat.takeIf { it.isFile }?.readText() ?: return false
        val stateIndex = contents.lastIndexOf(')') + 2
        return stateIndex in contents.indices && contents[stateIndex] == 'Z'
    }

    /**
     * Verifies CR, LF, CRLF, trailing separators, and a separator split at a reader-buffer edge.
     */
    @Test
    fun `line endings and trailing empty lines match existing truncation semantics`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-lines")
        try {
            val source = "x".repeat(4095) + "\r\nsecond\rthird\n"
            val file = dir.resolve("line-endings.txt")
            file.toFile().writeBytes(source.toByteArray(Charsets.UTF_8))
            val result = executeWithTestDeadline(
                buildInput("cat", listOf(file.fileName.toString()), timeout = 2, maxLines = 10, maxBytes = 10_000),
                context(dir),
            )

            assertSuccess(result)
            assertEquals(
                truncateLinesAndBytes(source, maxLines = 10, maxBytes = 10_000).text,
                stringDetail(result, "stdout"),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies that an unterminated line is consumed without growing retained output beyond limits.
     */
    @Test
    fun `long unterminated line is drained with bounded retention`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-long-line")
        try {
            val source = "LONG_LINE_PREFIX" + "x".repeat(256 * 1024) + "LONG_LINE_TAIL"
            val file = dir.resolve("long-line.txt")
            file.toFile().writeBytes(source.toByteArray(Charsets.UTF_8))
            val result = executeWithTestDeadline(
                buildInput("cat", listOf(file.fileName.toString()), timeout = 2, maxLines = 2, maxBytes = 64),
                context(dir),
            )

            assertSuccess(result)
            val stdout = stringDetail(result, "stdout")
            assertTrue(stdout.startsWith("LONG_LINE_PREFIX"))
            assertFalse(stdout.contains("LONG_LINE_TAIL"))
            assertTrue(stdout.toByteArray(Charsets.UTF_8).size <= 64)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies replacement decoding for malformed UTF-8 remains compatible with the process reader.
     */
    @Test
    fun `malformed utf8 uses replacement characters`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-malformed-utf8")
        try {
            val file = dir.resolve("malformed.bin")
            file.toFile().writeBytes(byteArrayOf(0xC3.toByte(), 0x28, 0x41))
            val result = executeWithTestDeadline(
                buildInput("cat", listOf(file.fileName.toString()), timeout = 2, maxBytes = 16),
                context(dir),
            )

            assertSuccess(result)
            assertEquals("\uFFFD(A", stringDetail(result, "stdout"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies supplementary code points remain whole when a reader buffer splits their surrogates.
     */
    @Test
    fun `supplementary code points remain intact across reader buffer boundaries`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-supplementary")
        try {
            val source = "a".repeat(4095) + "😀\n"
            val file = dir.resolve("supplementary.txt")
            file.toFile().writeBytes(source.toByteArray(Charsets.UTF_8))
            val result = executeWithTestDeadline(
                buildInput("cat", listOf(file.fileName.toString()), timeout = 2, maxLines = 2, maxBytes = 4_100),
                context(dir),
            )

            assertSuccess(result)
            assertEquals(source, stringDetail(result, "stdout"))
            assertTrue(stringDetail(result, "stdout").toByteArray(Charsets.UTF_8).size <= 4_100)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Demonstrates that reader threads do not depend on the caller's IO dispatcher having two threads.
     */
    @Test
    fun `large output succeeds with a single thread caller dispatcher`() = runTest {
        val dir = createTempDirectory("run-command-single-dispatcher")
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val (command, args) = largeOutputCommand(stderr = false, byteCount = 96 * 1024)
            val result = executeWithTestDeadline(
                buildInput(command, args, timeout = 2, maxBytes = 32),
                context(dir).copy(ioDispatcher = dispatcher),
            )

            assertSuccess(result)
            assertEquals(0, result.details?.jsonObject?.get("exitCode")?.jsonPrimitive?.int)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies cancellation rethrows while cleaning a running root and its visible child.
     */
    @Test
    fun `cancellation cleans up the process and is rethrown`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-cancellation")
        val pidFile = dir.resolve("child.pid")
        var childPid: Long? = null
        var commandJob: Job? = null
        try {
            val script = $$"sleep 30 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; wait"
            val deferred = async(Dispatchers.Default) {
                tool.execute(
                    buildInput("sh", listOf("-c", script), timeout = 30),
                    context(dir),
                )
            }
            commandJob = deferred
            childPid = waitForPid(pidFile)
            deferred.cancel(CancellationException("test cancellation"))
            assertFailsWith<CancellationException> { deferred.await() }
            val recordedPid = childPid
            assertTrue(waitForProcessExit(recordedPid), "cancelled descendant survived cleanup")
        } finally {
            commandJob?.cancelAndJoin()
            cleanupRecordedProcess(pidFile, childPid)
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Verifies a descendant holding an inherited pipe is terminated after the root has exited.
     */
    @Test
    fun `post exit cleanup terminates descendant holding inherited pipe`() = runTest {
        if (isWindows) return@runTest
        val dir = createTempDirectory("run-command-post-exit-descendant")
        val pidFile = dir.resolve("child.pid")
        var childPid: Long? = null
        try {
            val script = $$"sleep 30 & child=$!; printf '%s' \"$child\" > '$${pidFile}'; sleep 0.2; exit 0"
            val result = executeWithTestDeadline(
                buildInput("sh", listOf("-c", script), timeout = 1),
                context(dir),
            )
            childPid = waitForPid(pidFile)

            assertError(result, BuiltInToolExecutionError.TIMEOUT)
            val recordedPid = childPid
            assertTrue(waitForProcessExit(recordedPid), "post-exit descendant survived cleanup")
        } finally {
            cleanupRecordedProcess(pidFile, childPid)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run_command details JSON contains capped stdout and stderr when output is large`() = runTest {
        val dir = createTempDirectory("run-command-test")
        try {
            val file = dir.resolve("multiline.txt")
            file.writeText("line1\nline2\nline3\nline4\nline5", Charsets.UTF_8)
            val (cmd, argsList) = if (isWindows) {
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
                context(dir)
            )

            assertSuccess(result)
            val details = result.details ?: throw AssertionError("expected details")
            val stdout = details["stdout"]?.jsonPrimitive?.content ?: ""
            val stdoutLines = stdout.lines().filter { it.isNotEmpty() }
            assertTrue(stdoutLines.size <= 2, "details stdout should be capped to maxLines; got: $stdout")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}