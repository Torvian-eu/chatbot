package eu.torvian.chatbot.worker.builtin.impl

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.common.models.tool.BuiltInToolCatalog
import eu.torvian.chatbot.worker.builtin.BuiltInTool
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionContext
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import eu.torvian.chatbot.worker.builtin.validation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.*

/**
 * Executes a process inside the worker workspace.
 *
 * The command runs with the workspace as the CWD and is subject to a timeout (defaulting to the
 * worker-configured default). The worker relies on its deployment environment (Docker, VM, etc.)
 * to provide additional isolation.
 */
class RunCommandTool : BuiltInTool {
    override val name: String = "run_command"
    override val description: String = BuiltInToolCatalog.specFor(name)!!.description
    override val inputSchema: JsonObject = BuiltInToolCatalog.specFor(name)!!.inputSchema

    override suspend fun execute(input: JsonObject, context: BuiltInToolExecutionContext): BuiltInToolExecutionResult {
        // Accumulate all INVALID_INPUT validation errors before failing, so the LLM can see
        // every issue at once instead of fixing them one at a time.
        val validationErrors = mutableListOf<String>()

        // Define the set of known/valid parameter names for this tool
        val validKeys = setOf("command", "args", "timeout", "maxLines", "maxBytes")
        addUnknownParameterErrors(input, validKeys, validationErrors)

        val command = parseRequiredString(input, "command", validationErrors)
        if (command != null) {
            // Detect common LLM misuse: the full command line (with arguments) was placed
            // in the `command` field instead of being split into `command` + `args`.
            // Valid executable names passed to ProcessBuilder (which does not invoke a shell)
            // never contain whitespace.
            if (command.any { it.isWhitespace() }) {
                validationErrors.add(
                    "The 'command' field must be a single executable name without spaces " +
                            "(e.g. \"echo\", \"sh\", \"/usr/bin/git\"). Arguments should be provided " +
                            "in the 'args' field. Received: \"$command\". " +
                            "If you need to run a shell command with arguments, invoke the shell " +
                            "explicitly (e.g. command=\"sh\", args=[\"-c\", \"ls -la\"]) or pass " +
                            "arguments individually (e.g. command=\"echo\", args=[\"hello\", \"world\"])."
                )
            }
        }

        val timeoutSeconds = parseOptionalLong(
            input,
            "timeout",
            defaultValue = context.defaultCommandTimeoutSeconds,
            validationErrors = validationErrors,
        )
        val args = parseStringArray(input, "args", validationErrors)
        val maxLines = parseOptionalInt(input, "maxLines", defaultValue = 50, validationErrors)
        if (maxLines <= 0) {
            validationErrors.add("Argument 'maxLines' must be > 0")
        }
        val maxBytes = parseOptionalInt(input, "maxBytes", defaultValue = 2000, validationErrors)
        if (maxBytes <= 0) {
            validationErrors.add("Argument 'maxBytes' must be > 0")
        }

        if (timeoutSeconds <= 0) {
            validationErrors.add("timeout must be > 0 seconds")
        }

        if (validationErrors.isNotEmpty()) {
            return invalidInputResult(validationErrors)
        }

        return withContext(context.ioDispatcher) {
            var process: Process? = null
            // Keep handles found while the root is alive; they remain useful after the root is
            // reaped, when descendants may already have been reparented.
            val capturedDescendants = LinkedHashMap<Long, ProcessHandle>()
            try {
                val processBuilder = ProcessBuilder(listOf(command) + args)
                    .directory(context.workspace.toFile())
                    .redirectErrorStream(false)
                process = processBuilder.start()
                capturedDescendants.putAll(snapshotDescendants(process))
                // This tool is deliberately noninteractive; EOF must be observable immediately.
                process.outputStream.close()

                executeStartedProcess(
                    process = process,
                    timeoutSeconds = timeoutSeconds,
                    maxLines = maxLines,
                    maxBytes = maxBytes,
                    capturedDescendants = capturedDescendants,
                )
            } catch (e: CancellationException) {
                process?.let { cleanupStartedProcess(it, capturedDescendants) }
                throw e
            } catch (e: Exception) {
                process?.let { cleanupStartedProcess(it, capturedDescendants) }
                builtInToolErrorResult(
                    BuiltInToolExecutionError.EXECUTION_FAILED,
                    "Failed to run command: ${e.message}"
                )
            }
        }
    }

    /**
     * Runs a started process while two independent readers drain its output pipes.
     *
     * @param process The already-started command process.
     * @param timeoutSeconds Maximum time allowed for the root process to exit.
     * @param maxLines Maximum number of lines retained from each output stream.
     * @param maxBytes Maximum UTF-8 byte count retained from each output stream.
     * @param capturedDescendants Handles observed while the root was alive.
     * @return The stable tool result for successful completion or a timeout.
     * @throws CancellationException If the caller cancels the command; cleanup is completed first.
     */
    private suspend fun executeStartedProcess(
        process: Process,
        timeoutSeconds: Long,
        maxLines: Int,
        maxBytes: Int,
        capturedDescendants: MutableMap<Long, ProcessHandle>,
    ): BuiltInToolExecutionResult {
        // A dedicated pair prevents the blocking wait on the caller dispatcher from starving either reader.
        val readerExecutor = Executors.newFixedThreadPool(2) { runnable ->
            // A stuck native read must not keep the worker JVM alive after bounded cleanup returns.
            Thread(runnable, "run-command-output-reader").apply { isDaemon = true }
        }
        val readerFutures = mutableListOf<Future<TruncationResult>>()

        return try {
            readerFutures += readerExecutor.submit<TruncationResult> {
                BoundedOutputCollector(maxLines, maxBytes).collect(process.inputStream)
            }
            readerFutures += readerExecutor.submit<TruncationResult> {
                BoundedOutputCollector(maxLines, maxBytes).collect(process.errorStream)
            }
            // The command deadline covers root execution and the normal post-exit pipe drain. The
            // cleanup grace below is intentionally separate because forced termination can block.
            val commandDeadline = deadlineAfterTimeout(timeoutSeconds)
            while (true) {
                observeReaderFailures(readerFutures)
                if (!process.isAlive) break

                // Refresh while the root is alive so children created shortly after start are not
                // lost when the root exits and the operating system reparents them.
                captureDescendants(process, capturedDescendants)
                val remaining = remainingNanos(commandDeadline)
                if (remaining <= 0L) {
                    throw TimeoutException("Command deadline exceeded")
                }
                val waitNanos = minOf(remaining, READER_FAILURE_POLL_NANOS)
                val finished = runInterruptible {
                    process.waitFor(waitNanos, TimeUnit.NANOSECONDS)
                }
                if (finished) break
            }
            observeReaderFailures(readerFutures)

            val stdoutResult = runInterruptible {
                getUntilDeadline(readerFutures[0], commandDeadline)
            }
            val stderrResult = runInterruptible {
                getUntilDeadline(readerFutures[1], commandDeadline)
            }
            val exitCode = process.exitValue()
            val truncatedStdout = stdoutResult.text
            val truncatedStderr = stderrResult.text

            val outputBodyBeforeTruncation = buildString {
                append("exitCode: ").append(exitCode).append('\n')
                append("--- stdout ---\n").append(truncatedStdout)
                if (truncatedStderr.isNotEmpty()) {
                    append("\n--- stderr ---\n").append(truncatedStderr)
                }
            }

            val truncationResult = truncateLinesAndBytes(outputBodyBeforeTruncation, maxLines, maxBytes)
            val outputBody = truncationResult.text
            val linesShown = truncationResult.linesShown
            val bytesShown = truncationResult.bytesShown
            val truncated = truncationResult.isTruncated || stdoutResult.isTruncated || stderrResult.isTruncated

            val notice = if (truncated) {
                formatTruncationNotice(linesShown, bytesShown)
            } else {
                ""
            }
            val output = outputBody + notice

            val details = buildJsonObject {
                put("stdout", truncatedStdout)
                put("stderr", truncatedStderr)
                put("exitCode", exitCode)
                put("timeoutSeconds", timeoutSeconds)
                put("truncated", truncated)
            }
            BuiltInToolExecutionResult(
                output = output,
                isError = exitCode != 0,
                errorMessage = if (exitCode != 0) "Command exited with code $exitCode" else null,
                errorCode = if (exitCode != 0) BuiltInToolExecutionError.EXECUTION_FAILED else null,
                details = details,
            )
        } catch (e: CancellationException) {
            cleanupProcessAndReaders(process, readerFutures, readerExecutor, capturedDescendants)
            throw e
        } catch (_: TimeoutException) {
            cleanupProcessAndReaders(process, readerFutures, readerExecutor, capturedDescendants)
            builtInToolErrorResult(
                BuiltInToolExecutionError.TIMEOUT,
                "Command exceeded timeout of $timeoutSeconds seconds"
            )
        } catch (e: Exception) {
            cleanupProcessAndReaders(process, readerFutures, readerExecutor, capturedDescendants)
            builtInToolErrorResult(
                BuiltInToolExecutionError.EXECUTION_FAILED,
                "Failed to run command: ${e.message}"
            )
        } finally {
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
            readerExecutor.shutdownNow()
        }
    }

    /**
     * Captures descendants visible immediately after a process starts.
     *
     * @param process Root process whose currently visible descendants are copied.
     * @return A PID-keyed snapshot that remains usable if the root later exits.
     */
    private fun snapshotDescendants(process: Process): MutableMap<Long, ProcessHandle> =
        LinkedHashMap<Long, ProcessHandle>().also { captured ->
            captureDescendants(process, captured)
        }

    /**
     * Adds the root's currently visible descendants to a retained snapshot.
     *
     * The Java process-tree view is inherently racy: a child may start between scans or detach and
     * be reparented. Retaining every observed handle still closes the important root-exit race,
     * but this remains best-effort cleanup rather than a security boundary.
     *
     * @param process Root process whose descendants are inspected.
     * @param captured Mutable PID-keyed snapshot updated without removing earlier handles.
     */
    private fun captureDescendants(
        process: Process,
        captured: MutableMap<Long, ProcessHandle>,
    ) {
        val root = process.toHandle()
        runCatching {
            root.descendants().forEach { handle -> captured.putIfAbsent(handle.pid(), handle) }
        }
    }

    /**
     * Reports an unexpected reader failure before the root process has completed.
     *
     * A completed successful reader is harmless, but an exceptional future means the associated
     * pipe is no longer being drained and must select the existing execution-failure cleanup path.
     *
     * @param readerFutures Blocking stdout/stderr reader futures to inspect.
     * @throws CancellationException If a reader propagated coroutine cancellation.
     * @throws Exception If a reader failed unexpectedly.
     */
    private fun observeReaderFailures(readerFutures: Collection<Future<*>>) {
        readerFutures.forEach { future ->
            if (!future.isDone) return@forEach
            if (future.isCancelled) {
                throw IllegalStateException("Output reader was cancelled unexpectedly")
            }
            try {
                future.get()
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                if (cause is CancellationException) throw cause
                throw cause
            }
        }
    }

    /**
     * Computes a monotonic deadline after the configured command duration.
     *
     * @param timeoutSeconds Positive command timeout in seconds.
     * @return Saturated monotonic deadline in nanoseconds.
     */
    private fun deadlineAfterTimeout(timeoutSeconds: Long): Long =
        deadlineAfterNanos(TimeUnit.SECONDS.toNanos(timeoutSeconds))

    /**
     * Computes a monotonic deadline without allowing nanosecond addition to overflow.
     *
     * @param durationNanos Non-negative duration to add to the current monotonic time.
     * @return Saturated monotonic deadline in nanoseconds.
     */
    private fun deadlineAfterNanos(durationNanos: Long): Long {
        val now = System.nanoTime()
        return if (durationNanos > 0L && now > Long.MAX_VALUE - durationNanos) {
            Long.MAX_VALUE
        } else {
            now + durationNanos
        }
    }

    /**
     * Returns non-negative time remaining before a monotonic deadline.
     *
     * @param deadline Deadline previously produced by [deadlineAfterNanos].
     * @return Remaining nanoseconds, or zero after the deadline.
     */
    private fun remainingNanos(deadline: Long): Long =
        (deadline - System.nanoTime()).coerceAtLeast(0L)

    /**
     * Cleans up a started process when reader setup itself fails.
     *
     * @param process Root process to terminate and reap.
     * @param capturedDescendants Handles observed while the root was alive.
     */
    private suspend fun cleanupStartedProcess(
        process: Process,
        capturedDescendants: MutableMap<Long, ProcessHandle>,
    ) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            val deadline = deadlineAfterNanos(CLEANUP_GRACE_NANOS)
            terminateProcessTree(process, capturedDescendants, deadline)
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
        }
    }

    /**
     * Terminates a process tree and stops readers without allowing cleanup to extend indefinitely.
     *
     * @param process Root process whose streams are being read.
     * @param readerFutures Reader tasks that may be blocked on the process pipes.
     * @param readerExecutor Executor owning the blocking reader tasks.
     * @param capturedDescendants Handles observed while the root was alive.
     */
    private suspend fun cleanupProcessAndReaders(
        process: Process,
        readerFutures: List<Future<*>>,
        readerExecutor: ExecutorService,
        capturedDescendants: MutableMap<Long, ProcessHandle>,
    ) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            val deadline = deadlineAfterNanos(CLEANUP_GRACE_NANOS)
            terminateProcessTree(process, capturedDescendants, deadline)
            // Closing the streams is required before cancelling because interruption alone may not
            // interrupt a blocking Java pipe read.
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
            readerFutures.forEach { future -> future.cancel(true) }
            readerExecutor.shutdownNow()
            val remaining = remainingNanos(deadline)
            if (remaining > 0L) {
                runCatching { readerExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS) }
            }
        }
    }

    /**
     * Signals visible descendants before the root and waits briefly for the process tree to settle.
     * Java process handles are intentionally best effort rather than a security boundary: detached
     * or reparented descendants may no longer be visible to this process.
     *
     * @param process Root process to terminate and reap.
     * @param capturedDescendants Handles observed while the root was alive.
     * @param deadline Monotonic deadline shared by all cleanup waits.
     */
    private fun terminateProcessTree(
        process: Process,
        capturedDescendants: MutableMap<Long, ProcessHandle>,
        deadline: Long,
    ) {
        // Capture before signalling the root. A child can be reparented as soon as the root exits,
        // so a post-exit descendants() call alone cannot find a pipe-holding child.
        captureDescendants(process, capturedDescendants)
        var descendants = capturedDescendants.values.toList()
        descendants.forEach(::destroyForcibly)

        // Let ordinary parents observe and reap killed children without delaying root termination.
        val parentReapDeadline = minOf(deadline, deadlineAfterNanos(DESCENDANT_PRE_ROOT_WAIT_NANOS))
        waitForDescendants(descendants, parentReapDeadline)
        runCatching { process.destroyForcibly() }
        waitForProcessTree(process, descendants, deadline)

        // This rescan catches children created during cleanup while the root was still visible.
        captureDescendants(process, capturedDescendants)
        descendants = capturedDescendants.values.toList()
        descendants.forEach(::destroyForcibly)
        waitForProcessTree(process, descendants, deadline)
    }

    /**
     * Forcefully signals a descendant while suppressing platform-specific cleanup failures.
     *
     * @param handle Process handle that should be terminated.
     */
    private fun destroyForcibly(handle: ProcessHandle) {
        runCatching { handle.destroyForcibly() }
    }

    /**
     * Waits for descendant handles until the shared cleanup deadline.
     *
     * @param descendants Handles captured before or after root termination.
     * @param deadline Monotonic deadline for this cleanup pass.
     */
    private fun waitForDescendants(descendants: Collection<ProcessHandle>, deadline: Long) {
        descendants
            .distinctBy { handle -> handle.pid() }
            .forEach { handle ->
                val nanosLeft = remainingNanos(deadline)
                runCatching {
                    if (nanosLeft > 0L && handle.isAlive) {
                        handle.onExit().get(nanosLeft, TimeUnit.NANOSECONDS)
                    }
                }
            }
    }

    /**
     * Waits for the root and descendant handles until the shared cleanup deadline.
     *
     * @param process Root process to reap with the authoritative process wait operation.
     * @param descendants Handles captured before or after root termination.
     * @param deadline Monotonic deadline for this cleanup pass.
     */
    private fun waitForProcessTree(
        process: Process,
        descendants: Collection<ProcessHandle>,
        deadline: Long,
    ) {
        val remaining = remainingNanos(deadline)
        if (remaining > 0L) {
            runCatching { process.waitFor(remaining, TimeUnit.NANOSECONDS) }
        }
        waitForDescendants(descendants, deadline)
    }

    /**
     * Closes a process stream while suppressing expected errors during forced cleanup.
     *
     * @param input The stream to close.
     */
    private fun closeQuietly(input: InputStream) {
        runCatching { input.close() }
    }

    /**
     * Gets a reader result without exceeding the shared command deadline.
     *
     * @param future Reader future whose result is required.
     * @param deadline Monotonic deadline shared by both output streams.
     * @return The bounded stream result.
     * @throws TimeoutException If the future does not complete before [deadline].
     */
    private fun getUntilDeadline(
        future: Future<TruncationResult>,
        deadline: Long,
    ): TruncationResult {
        val remaining = remainingNanos(deadline)
        if (remaining <= 0L) throw TimeoutException("Process output drain deadline exceeded")
        return try {
            future.get(remaining, TimeUnit.NANOSECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
    }

    /**
     * Retains only the bounded, normalized prefix of one process output stream while continuing to
     * consume discarded data so the child can never block on a full pipe.
     *
     * @property maxLines Maximum logical lines retained.
     * @property maxBytes Maximum UTF-8 bytes retained.
     */
    private class BoundedOutputCollector(
        private val maxLines: Int,
        private val maxBytes: Int,
    ) {
        /** Retained normalized prefix; it never grows beyond the caller's byte budget. */
        private val retained = StringBuilder()

        /** Zero-based logical line currently being consumed. */
        private var currentLine = 0

        /** UTF-8 byte count of [retained]. */
        private var byteCount = 0

        /** Whether a code point already exceeded the byte budget, ending retained content. */
        private var byteLimitReached = false

        /** Whether any source content was excluded by either caller limit. */
        private var truncated = false

        /** High surrogate held until the next reader character completes or rejects the pair. */
        private var pendingHighSurrogate: Char? = null

        /** Whether the last separator was CR and a following LF must be coalesced. */
        private var pendingCarriageReturn = false

        /**
         * Reads [input] to EOF with fixed-size buffers and returns its bounded result.
         *
         * @param input Stream belonging to the command's stdout or stderr pipe.
         * @return A truncation result equivalent to the existing full-text helper.
         */
        fun collect(input: InputStream): TruncationResult {
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(READER_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (index in 0 until count) {
                        acceptChar(buffer[index])
                    }
                }
            }
            pendingHighSurrogate?.let { high -> acceptCodePoint(high.code) }
            pendingHighSurrogate = null

            val text = retained.toString()
            return TruncationResult(
                text = text,
                linesShown = if (text.isEmpty()) 0 else text.lines().size,
                bytesShown = byteCount,
                isTruncated = truncated,
            )
        }

        /**
         * Feeds one decoded UTF-16 code unit while preserving pairs split across reader buffers.
         *
         * @param value Decoded character from the incremental UTF-8 reader.
         */
        private fun acceptChar(value: Char) {
            val pending = pendingHighSurrogate
            if (pending != null) {
                pendingHighSurrogate = null
                if (Character.isLowSurrogate(value)) {
                    acceptCodePoint(Character.toCodePoint(pending, value))
                    return
                }
                acceptCodePoint(pending.code)
            }
            if (Character.isHighSurrogate(value)) {
                pendingHighSurrogate = value
            } else {
                acceptCodePoint(value.code)
            }
        }

        /**
         * Applies CR/LF normalization and retains or discards one decoded code point.
         *
         * @param codePoint Decoded Unicode code point, including replacement characters.
         */
        private fun acceptCodePoint(codePoint: Int) {
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false
                if (codePoint == '\n'.code) return
            }
            when (codePoint) {
                '\r'.code -> {
                    acceptLineSeparator()
                    pendingCarriageReturn = true
                }

                '\n'.code -> {
                    acceptLineSeparator()
                }

                else -> {
                    acceptContent(codePoint)
                }
            }
        }

        /**
         * Retains a normalized line separator when it belongs to the selected line prefix.
         */
        private fun acceptLineSeparator() {
            if (currentLine + 1 >= maxLines) {
                truncated = true
            } else if (!appendCodePoint('\n'.code)) {
                truncated = true
            }
            currentLine++
        }

        /**
         * Retains content only while both caller-provided limits still allow it.
         *
         * @param codePoint Decoded code point to retain when it fits.
         */
        private fun acceptContent(codePoint: Int) {
            if (currentLine >= maxLines || !appendCodePoint(codePoint)) {
                truncated = true
            }
        }

        /**
         * Appends one complete code point when its UTF-8 width fits the remaining byte budget.
         *
         * @param codePoint Code point to append.
         * @return True when the complete code point was retained.
         */
        private fun appendCodePoint(codePoint: Int): Boolean {
            if (byteLimitReached) return false
            val width = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (byteCount + width > maxBytes) {
                byteLimitReached = true
                return false
            }
            if (codePoint <= Char.MAX_VALUE.code) {
                retained.append(codePoint.toChar())
            } else {
                retained.append(Character.toChars(codePoint).concatToString())
            }
            byteCount += width
            return true
        }
    }

    /** Constants defining bounded reader and process cleanup behavior. */
    private companion object {
        /** Fixed character buffer used by each blocking process-output reader. */
        const val READER_BUFFER_SIZE: Int = 4096

        /** Poll interval that bounds how long a failed reader can remain unobserved. */
        const val READER_FAILURE_POLL_NANOS: Long = 50_000_000L

        /** Short opportunity for a parent to reap signalled descendants before root termination. */
        const val DESCENDANT_PRE_ROOT_WAIT_NANOS: Long = 100_000_000L

        /** Shared monotonic cleanup budget for process-tree termination and reaping. */
        const val CLEANUP_GRACE_NANOS: Long = 2_000_000_000L
    }
}