package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Provides stateless fixtures and assertions shared by the run-command JVM tests.
 *
 * The object deliberately stores no process, directory, coroutine, or tool state so the test
 * classes remain independent when the worker test suite runs in parallel.
 */
internal object RunCommandTestSupport {
    /** Whether the current test host uses Windows command-line semantics. */
    internal val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

    /**
     * Builds a platform-appropriate command and arguments that echo [text].
     *
     * @param text Text passed to the echo command.
     * @return The executable and argument list for [RunCommandTool].
     */
    internal fun echoCommand(text: String): Pair<String, List<String>> =
        if (isWindows) "cmd" to listOf("/c", "echo", text) else "echo" to listOf(text)

    /**
     * Builds a platform-appropriate command and arguments that return [code].
     *
     * @param code Exit code returned by the command.
     * @return The executable and argument list for [RunCommandTool].
     */
    internal fun exitCommand(code: Int): Pair<String, List<String>> =
        if (isWindows) "cmd" to listOf("/c", "exit", code.toString()) else "sh" to listOf("-c", "exit $code")

    /**
     * Builds a command that blocks long enough to exercise the tool timeout.
     *
     * Windows uses a plain `cmd` loop because `choice` and `timeout` can leave a conhost child
     * behind after forced destruction; Linux uses a long-lived `sleep` process.
     *
     * @return The executable and argument list for [RunCommandTool].
     */
    internal fun hangCommand(): Pair<String, List<String>> =
        if (isWindows) {
            "cmd" to listOf("/c", "for", "/l", "%i", "in", "(1,1,2147483647)", "do", "@(echo", "%i", ">nul)")
        } else {
            "sleep" to listOf("30")
        }

    /**
     * Builds the JSON input object accepted by [RunCommandTool.execute].
     *
     * @param command Command to execute.
     * @param args Optional command arguments.
     * @param timeout Optional timeout in seconds.
     * @param maxLines Optional maximum number of retained output lines.
     * @param maxBytes Optional maximum number of retained UTF-8 output bytes.
     * @return The input JSON object for the tool.
     */
    internal fun buildInput(
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
     * Creates an execution context rooted at [workspace].
     *
     * @param workspace Working directory exposed to the command.
     * @param ioDispatcher Dispatcher used by the command tool for process work.
     * @return An isolated command execution context with the original test defaults.
     */
    internal fun context(
        workspace: Path,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): BuiltInToolExecutionContext =
        BuiltInToolExecutionContext(
            workspace = workspace,
            defaultCommandTimeoutSeconds = 60,
            defaultSearchTimeoutSeconds = 5,
            ioDispatcher = ioDispatcher,
        )

    /**
     * Asserts that [result] is successful and returns its textual output.
     *
     * @param result Result returned by the command tool.
     * @return Result output, or an empty string when no output was supplied.
     */
    internal fun assertSuccess(result: BuiltInToolExecutionResult): String {
        assertTrue(!result.isError, "Expected success but got error: ${result.errorMessage}")
        return result.output ?: ""
    }

    /**
     * Asserts that [result] is an error carrying [code].
     *
     * @param result Result returned by the command tool.
     * @param code Expected logical error code.
     */
    internal fun assertError(result: BuiltInToolExecutionResult, code: String) {
        assertTrue(result.isError, "Expected error but got success: ${result.output}")
        assertEquals(code, result.errorCode, "Unexpected error code; message=${result.errorMessage}")
    }

    /**
     * Executes [input] with the fixed outer deadline used to prevent a process regression from
     * hanging the test worker indefinitely.
     *
     * The outer timeout runs on [Dispatchers.Default], independently of the command timeout and
     * caller dispatcher, so pipe-draining tests also cover dispatcher isolation.
     *
     * @param tool Tool instance under test.
     * @param input Validated command input.
     * @param executionContext Isolated workspace and execution configuration.
     * @return Result produced by [RunCommandTool.execute].
     * @throws kotlinx.coroutines.TimeoutCancellationException When the test-level deadline expires.
     */
    internal suspend fun executeWithTestDeadline(
        tool: RunCommandTool,
        input: JsonObject,
        executionContext: BuiltInToolExecutionContext,
    ): BuiltInToolExecutionResult = withContext(Dispatchers.Default) {
        withTimeout(8_000.milliseconds) {
            tool.execute(input, executionContext)
        }
    }

    /**
     * Builds an OS-aware command that emits a bounded request-independent output stream.
     *
     * @param stderr Whether output is redirected to standard error.
     * @param byteCount Approximate number of filler bytes to emit.
     * @param prefix Distinctive text emitted before the filler.
     * @param suffix Distinctive text emitted after the filler.
     * @param exitCode Exit code returned by the command.
     * @return The executable and argument list for [RunCommandTool].
     */
    internal fun largeOutputCommand(
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
     * Builds a command whose independent output streams both exceed normal pipe capacity.
     *
     * @param byteCount Approximate bytes emitted on each stream.
     * @return The executable and argument list for [RunCommandTool].
     */
    internal fun bothStreamsLargeOutputCommand(byteCount: Int): Pair<String, List<String>> {
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
     * Reads a string-valued detail from [result].
     *
     * @param result Tool result containing structured details.
     * @param key Name of the detail to read.
     * @return Detail content, or an empty string when the value is absent.
     */
    internal fun stringDetail(result: BuiltInToolExecutionResult, key: String): String =
        result.details?.jsonObject?.get(key)?.jsonPrimitive?.content ?: ""

    /**
     * Builds deliberately invalid input whose `args` value is a single JSON string.
     *
     * @param text Text placed in the invalid argument value.
     * @return JSON input with a platform-valid command and string-form arguments.
     */
    internal fun singleStringArgsInput(text: String): JsonObject =
        buildJsonObject {
            if (isWindows) {
                put("command", "cmd")
                put("args", "/c echo $text")
            } else {
                put("command", "echo")
                put("args", text)
            }
        }

    /**
     * Waits for a command-written PID file without assuming a fixed startup duration.
     *
     * @param pidFile File expected to contain a decimal process identifier.
     * @param timeoutMillis Maximum wall-clock time to wait.
     * @return The recorded process identifier.
     * @throws AssertionError If no valid PID is recorded before the deadline.
     */
    internal fun waitForPid(pidFile: Path, timeoutMillis: Long = 3_000): Long {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            val pid = pidFile.toFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            if (pid != null) return pid
            Thread.sleep(25)
        }
        throw AssertionError("PID was not recorded in $pidFile")
    }

    /**
     * Polls whether [pid] has exited after command cleanup.
     *
     * Linux PID 1 can retain a killed orphan as a zombie in this test container. Such a process is
     * treated as terminated because it no longer runs or consumes the tested process resources.
     *
     * @param pid Process identifier to inspect.
     * @param timeoutMillis Maximum wall-clock time to poll.
     * @return True when the process is absent, stopped, or a Linux zombie.
     */
    internal fun waitForProcessExit(pid: Long, timeoutMillis: Long = 3_000): Boolean {
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
     * Makes a best-effort attempt to terminate the process recorded by [pidFile].
     *
     * @param pidFile File containing the command's PID.
     * @param fallbackPid PID already captured by the test, if any.
     */
    internal fun cleanupRecordedProcess(pidFile: Path, fallbackPid: Long?) {
        val pid = fallbackPid ?: pidFile.toFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        pid?.let { recordedPid ->
            ProcessHandle.of(recordedPid).ifPresent { handle ->
                if (handle.isAlive) handle.destroyForcibly()
            }
        }
    }

    /**
     * Detects a terminated zombie process through Linux procfs.
     *
     * @param pid Process identifier to inspect.
     * @return True when procfs reports the process state as zombie.
     */
    internal fun isLinuxZombie(pid: Long): Boolean {
        if (isWindows) return false
        val stat = Path.of("/proc", pid.toString(), "stat").toFile()
        val contents = stat.takeIf { it.isFile }?.readText() ?: return false
        val stateIndex = contents.lastIndexOf(')') + 2
        return stateIndex in contents.indices && contents[stateIndex] == 'Z'
    }
}
