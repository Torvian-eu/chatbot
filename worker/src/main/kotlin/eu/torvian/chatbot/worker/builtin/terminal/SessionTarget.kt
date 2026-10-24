package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.builtin.command.CommandTerminator
import eu.torvian.chatbot.worker.builtin.command.TrackedCommand
import eu.torvian.chatbot.worker.builtin.command.remainingNanos
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.logging.log4j.Logger

/**
 * Status of one target, before the session decides whether it is attached or closed.
 */
internal enum class TargetStatus {
    /** The process is alive, or its output is still being produced or drained. */
    RUNNING,

    /** The process is alive and has been silent for the quiescence window. */
    QUIET,

    /** The process ended and its reader has drained, or the drain grace expired. */
    EXITED,
}

/**
 * Cheap state of one target, used by the settle loop before it consumes output.
 *
 * @property status Status a consuming read would currently report.
 * @property pendingBytes UTF-8 bytes produced but not yet returned.
 * @property wouldTruncate Whether the per-call line or byte cap would cut the available backlog.
 * @property quietForMillis Milliseconds since output was last observed.
 * @property producedOutputVersion Output version used to detect output produced by an action.
 * @property alive Whether the process is still running.
 */
internal data class TargetPreview(
    val status: TargetStatus,
    val pendingBytes: Int,
    val wouldTruncate: Boolean,
    val quietForMillis: Long,
    val producedOutputVersion: Long,
    val alive: Boolean,
)

/**
 * One consumed chunk of a target, with the state its status was derived from.
 *
 * Everything here is read under the target's own lock, so a read and its status are one observation
 * of that process rather than two interleaved ones.
 *
 * @property status Status of this target at the moment of the read.
 * @property text Output returned by this read, including any dropped-output notice.
 * @property hasNewOutput Whether [text] is non-empty.
 * @property pendingBytes UTF-8 bytes still pending after the read.
 * @property droppedBytes UTF-8 bytes evicted before the returned text.
 * @property bufferBytes Sanitized bytes currently retained by this target's buffer.
 * @property quietForMillis Milliseconds since output was last observed.
 * @property exitCode Recorded exit code, or null while the process has not ended.
 * @property tuiDetected Whether an alternate-screen or escape-dense stream was observed.
 */
internal data class TargetRead(
    val status: TargetStatus,
    val text: String,
    val hasNewOutput: Boolean,
    val pendingBytes: Int,
    val droppedBytes: Long,
    val bufferBytes: Int,
    val quietForMillis: Long,
    val exitCode: Int?,
    val tuiDetected: Boolean,
)

/**
 * One PTY process together with its output buffer, reader thread, cursor and terminator.
 *
 * A session owns a shell target and, while a credential profile is attached, a profile target too.
 * Each target keeps its own buffer and cursor, so output produced before, during and after an attach
 * is never interleaved, and each target is torn down through the same strategy that started it.
 *
 * @property name Target name used in log lines and reader-thread names.
 * @param process Started PTY process; the target owns its streams.
 * @param terminator Strategy terminating this process tree for the resolved isolation state.
 * @param buffer Bounded sanitized output storage.
 * @param sanitizer Stateful control-sequence filter run on the reader thread.
 * @param quietMillis Silence window after which the process is considered quiet.
 * @param clock Monotonic nanosecond clock, injectable for deterministic tests.
 * @param logger Receives lifecycle and leak warnings.
 */
internal class SessionTarget(
    val name: String,
    private val process: Process,
    private val terminator: CommandTerminator,
    private val buffer: OutputRingBuffer,
    private val sanitizer: TerminalOutputSanitizer,
    private val quietMillis: Long,
    private val clock: () -> Long = System::nanoTime,
    private val logger: Logger,
) {

    /** Guards this target's buffer, cursor, lifecycle, exit code and timers. */
    private val stateLock = Any()

    /** Absolute character offset of the next unread character. */
    private var cursor: Long = 0L

    /** UTF-8 byte offset of [cursor], so drop notices can report bytes after eviction. */
    private var cursorBytes: Long = 0L

    /** Whether the reader has drained this target. */
    private var drainedValue = false

    /** Recorded process exit status, or null while it is unknown. */
    private var exitCodeValue: Int? = null

    /**
     * Monotonic time the process was first observed dead, or null while it is alive or unreported.
     *
     * It bounds how long a dead process may stay unreported while the reader is still draining.
     */
    private var processExitNanosValue: Long? = null

    /** Monotonic time output was last observed, used for the quiescence rule. */
    private var lastOutputNanos: Long = clock()

    /** Incremented whenever non-empty sanitized output is appended. */
    private var outputVersionValue: Long = 0L

    /** Guards against starting the reader thread more than once. */
    private val readerStarted = AtomicBoolean(false)

    /** Daemon thread that keeps this target's PTY drained until EOF or teardown. */
    private val readerThread: Thread = Thread(::readLoop, "terminal-session-reader-$name").apply {
        isDaemon = true
    }

    init {
        readerThread.start()
        readerStarted.set(true)
    }

    /** Whether the process is still running. */
    val alive: Boolean
        get() = process.isAlive

    /**
     * Whether this target's process ended and its reader drained, or the drain grace after exit
     * elapsed.
     */
    val exited: Boolean
        get() = synchronized(stateLock) {
            recordExitLocked()
            exitReportableLocked(clock())
        }

    /** Recorded exit code, or null while the process has not ended or the code is unavailable. */
    val exitCode: Int?
        get() = synchronized(stateLock) {
            recordExitLocked()
            exitCodeValue
        }

    /** Nanoseconds of the most recent output observed on this target. */
    val lastOutputNanosSnapshot: Long
        get() = synchronized(stateLock) { lastOutputNanos }

    /** Count of non-empty chunks appended so far, used to detect output produced by an action. */
    val outputVersion: Long
        get() = synchronized(stateLock) { outputVersionValue }

    /** Whether an alternate-screen or escape-dense stream was observed. */
    val tuiDetected: Boolean
        get() = synchronized(stateLock) { sanitizer.tuiDetected }

    /**
     * Writes input to this target verbatim.
     *
     * @param input Text to deliver; empty input is rejected by the caller.
     * @throws IOException When the process no longer accepts input.
     */
    @Throws(IOException::class)
    fun write(input: String) {
        val stream: OutputStream = process.outputStream
        stream.write(input.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    /**
     * Inspects this target without consuming output.
     *
     * @param maxLines Maximum lines a consuming read would return.
     * @param maxBytes Maximum bytes a consuming read would return.
     * @return Status, pending byte count and whether a read would be truncated by the caps.
     */
    fun preview(maxLines: Int, maxBytes: Int): TargetPreview = synchronized(stateLock) {
        val now = clock()
        val pendingBytes = pendingBytesLocked()
        val capped = buffer.read(cursor, maxBytes.coerceAtLeast(1), maxBytes.coerceAtLeast(1))
        val wouldTruncate = cutToLines(capped.text, maxLines).length < capped.text.length
        TargetPreview(
            status = statusLocked(pendingBytes, now),
            pendingBytes = pendingBytes,
            wouldTruncate = wouldTruncate,
            quietForMillis = quietForMillisLocked(now),
            producedOutputVersion = outputVersionValue,
            alive = process.isAlive && !drainedValue,
        )
    }

    /**
     * Consumes the next bounded chunk and advances this target's cursor.
     *
     * @param maxLines Maximum complete lines returned.
     * @param maxBytes Maximum UTF-8 bytes returned.
     * @return The consumed chunk with the state its status was derived from.
     */
    fun read(maxLines: Int, maxBytes: Int): TargetRead = synchronized(stateLock) {
        val now = clock()
        val chunk = buffer.read(cursor, maxBytes.coerceAtLeast(1), maxBytes.coerceAtLeast(1))
        val cut = cutToLines(chunk.text, maxLines)
        // When the cursor fell behind the retained window, the chunk starts at oldestRetained.
        // Advance over the dropped region as well, or the next read would re-emit this window.
        val droppedBytes = if (chunk.droppedChars > 0) buffer.oldestRetainedBytes - cursorBytes else 0L
        cursor += chunk.droppedChars + cut.length
        cursorBytes += droppedBytes + cut.encodeToByteArray().size
        val pendingBytes = pendingBytesLocked()
        val output = if (droppedBytes > 0) {
            droppedNotice(droppedBytes) + cut
        } else {
            cut
        }
        TargetRead(
            status = statusLocked(pendingBytes, now),
            text = output,
            hasNewOutput = output.isNotEmpty(),
            pendingBytes = pendingBytes,
            droppedBytes = droppedBytes,
            bufferBytes = buffer.retainedBytes,
            quietForMillis = quietForMillisLocked(now),
            exitCode = exitCodeValue,
            tuiDetected = sanitizer.tuiDetected,
        )
    }

    /**
     * Terminates this target's process tree, joins its reader and closes both master streams.
     *
     * Both streams are views of the same PTY master, so closing either one ends the reader's view of
     * the device and discards the bytes the child wrote but nobody read yet; the join therefore runs
     * first so that tail still reaches the buffer. The join is bounded by [deadline], and the closes
     * afterwards unblock a reader that never observes EOF, so teardown cannot hang.
     *
     * @param deadline Shared monotonic deadline for termination and the reader join.
     */
    fun teardown(deadline: Long) {
        try {
            terminator.terminate(TrackedCommand(process), deadline)
        } catch (e: Exception) {
            // Some PTY implementations cannot expose a ProcessHandle; fall back to the direct
            // handle so teardown still ends the child instead of leaving it alive.
            logger.warn("Terminal target {} termination was not confirmed: {}", name, e.message)
            runCatching { process.destroyForcibly() }
        }
        // A shutdown deadline can expire inside the terminator; never leave a live PTY behind.
        if (process.isAlive) runCatching { process.destroyForcibly() }
        joinReader(deadline)
        // Closing either master stream would end the reader immediately, so both closes come after
        // the drain; they still unblock a reader that outlived the deadline.
        closeQuietly(process.inputStream)
        closeQuietly(process.outputStream)
    }

    /**
     * Drains the PTY until EOF or teardown.
     *
     * Every read happens under this target's lock, which no action holds while it suspends, so a
     * concurrent teardown can join this thread.
     */
    private fun readLoop() {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                val chars = CharArray(READER_BUFFER_SIZE)
                while (true) {
                    val count = try {
                        reader.read(chars)
                    } catch (_: IOException) {
                        // A closed master stream ends the reader normally during teardown.
                        -1
                    }
                    if (count < 0) break
                    val text = sanitizer.feed(chars, count)
                    val now = clock()
                    synchronized(stateLock) {
                        if (text.isNotEmpty()) {
                            buffer.append(text)
                            lastOutputNanos = now
                            outputVersionValue++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Terminal target {} reader failed: {}", name, e.message)
        } finally {
            val exit = determineExitCode()
            synchronized(stateLock) {
                drainedValue = true
                if (exit != null) exitCodeValue = exit
            }
        }
    }

    /**
     * Reads the process exit code without blocking the reader indefinitely.
     *
     * @return Exit code when the process has ended, otherwise null.
     */
    private fun determineExitCode(): Int? {
        return runCatching {
            if (process.waitFor(EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS) || !process.isAlive) {
                process.exitValue()
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Joins the reader until the shared deadline; a reader that does not stop remains a daemon and
     * is reported as a leak.
     *
     * @param deadline Shared monotonic deadline in nanoseconds.
     */
    private fun joinReader(deadline: Long) {
        if (!readerStarted.get()) return
        val remaining = remainingNanos(deadline)
        if (remaining > 0L) {
            runCatching { readerThread.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)) }
        }
        if (readerThread.isAlive) {
            logger.warn("Terminal target {} reader did not stop during teardown", name)
        }
    }

    /**
     * @param pendingBytes Bytes still pending after the current cursor.
     * @param now Current monotonic time.
     * @return Status derived from the drained lifecycle, pending output and quiet time.
     */
    private fun statusLocked(pendingBytes: Int, now: Long): TargetStatus {
        recordExitLocked()
        if (exitReportableLocked(now)) return TargetStatus.EXITED
        // A dead process whose reader has not drained yet still has bytes in flight: report it as
        // running so the caller waits for them instead of seeing an ended target with no tail.
        if (processExitNanosValue != null) return TargetStatus.RUNNING
        if (pendingBytes > 0) return TargetStatus.RUNNING
        return if (quietForMillisLocked(now) >= quietMillis) TargetStatus.QUIET else TargetStatus.RUNNING
    }

    /**
     * Whether this target may be reported as exited.
     *
     * The drain is complete only when the reader reached EOF, because the child's last bytes enter
     * the buffer there. The gate is bounded by [READER_DRAIN_GRACE_NANOS] so a reader that never
     * observes EOF cannot keep the target from reporting its exit forever.
     *
     * @param now Current monotonic time.
     * @return True when the reader drained or the drain grace after process exit has elapsed.
     */
    private fun exitReportableLocked(now: Long): Boolean {
        if (drainedValue) return true
        val exitedAt = processExitNanosValue ?: return false
        return now - exitedAt >= READER_DRAIN_GRACE_NANOS
    }

    /**
     * Records the exit code and the exit timestamp once the process has ended, even when the reader
     * has not observed EOF yet.
     */
    private fun recordExitLocked() {
        if (process.isAlive) return
        if (processExitNanosValue == null) processExitNanosValue = clock()
        if (exitCodeValue == null) {
            exitCodeValue = runCatching { process.exitValue() }.getOrNull()
        }
    }

    /**
     * @param now Current monotonic time.
     * @return Milliseconds since output was last observed.
     */
    private fun quietForMillisLocked(now: Long): Long =
        ((now - lastOutputNanos).coerceAtLeast(0L) / NANOS_PER_MILLI)

    /**
     * @return UTF-8 bytes produced but not yet returned from the current cursor.
     */
    private fun pendingBytesLocked(): Int {
        val remaining = buffer.read(cursor, Int.MAX_VALUE, Int.MAX_VALUE)
        if (remaining.text.isEmpty()) return 0
        return remaining.text.encodeToByteArray().size
    }

    private companion object {
        /** Reader buffer size in characters. */
        const val READER_BUFFER_SIZE = 4096

        /** Bound on waiting for the process to report its exit code after EOF. */
        const val EXIT_WAIT_MILLIS = 500L

        /**
         * Bound on reporting an exit while the reader has not observed EOF, so a reader that never
         * drains delays the status instead of withholding it.
         */
        val READER_DRAIN_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(500L)

        /** Nanoseconds per millisecond. */
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * @param dropped Number of UTF-8 bytes that fell out of the retained window.
         * @return Notice prepended to the next returned chunk.
         */
        fun droppedNotice(dropped: Long): String =
            "[... $dropped bytes of output were dropped from this session's buffer ...]\n"

        /**
         * Clips [text] to at most [maxLines] complete lines.
         *
         * The cut is applied after the buffer read so the cursor advances by exactly the characters
         * returned; the remainder stays pending for the next action.
         *
         * @param text Candidate chunk from the ring buffer.
         * @param maxLines Maximum complete lines to retain.
         * @return [text] or its prefix ending after the [maxLines]-th newline.
         */
        fun cutToLines(text: String, maxLines: Int): String {
            if (maxLines <= 0 || text.isEmpty()) return ""
            var newlines = 0
            for (index in text.indices) {
                if (text[index] == '\n') {
                    newlines++
                    if (newlines == maxLines) {
                        return text.substring(0, index + 1)
                    }
                }
            }
            return text
        }

        /**
         * @param stream Stream to close during teardown.
         */
        fun closeQuietly(stream: java.io.Closeable?) {
            runCatching { stream?.close() }
        }
    }
}
