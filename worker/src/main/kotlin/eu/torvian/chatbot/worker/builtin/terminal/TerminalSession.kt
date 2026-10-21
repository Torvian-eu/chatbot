package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.worker.builtin.command.CommandTerminator
import eu.torvian.chatbot.worker.builtin.command.TrackedCommand
import eu.torvian.chatbot.worker.builtin.command.remainingNanos
import eu.torvian.chatbot.worker.config.TerminalSessionsConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/** Status reported to the model for one action. */
internal enum class TerminalStatus { RUNNING, QUIET, EXITED, CLOSED }

/** Lifecycle state stored by a session. */
internal enum class TerminalLifecycle { ALIVE, EXITED, CLOSED }

/** Reason a session was closed, reported in logs and the tombstone result. */
internal enum class TerminalCloseReason {
    /** The caller closed the session explicitly. */
    REQUESTED,

    /** The idle reaper closed the session. */
    IDLE_TIMEOUT,

    /** The session exceeded its absolute lifetime. */
    MAX_LIFETIME,

    /** The worker is shutting down. */
    SHUTDOWN,

    /** The `start` call that created the session was cancelled before it returned. */
    CANCELLED,
}

/**
 * Snapshot returned by one terminal action.
 *
 * @property sessionId Identifies the session this snapshot belongs to.
 * @property status Derived status for this read.
 * @property output Text returned by this action, possibly prefixed by a dropped-output notice.
 * @property exitCode Recorded exit code when the process ended.
 * @property hasNewOutput Whether this read returned any output.
 * @property quietForMillis Elapsed milliseconds since output was last observed.
 * @property truncated True exactly when [pendingBytes] is positive.
 * @property pendingBytes UTF-8 bytes produced but not yet returned.
 * @property droppedBytes UTF-8 bytes evicted before the returned text.
 * @property bufferBytes Sanitized bytes currently retained by the session buffer.
 * @property idleTimeoutSeconds Idle lifetime configured for this session.
 * @property tuiDetected Whether an alternate-screen or escape-dense stream was observed.
 * @property tuiHint Whether this read should show the one-time TUI warning.
 */
internal data class TerminalSnapshot(
    val sessionId: String,
    val status: TerminalStatus,
    val output: String,
    val exitCode: Int?,
    val hasNewOutput: Boolean,
    val quietForMillis: Long,
    val truncated: Boolean,
    val pendingBytes: Int,
    val droppedBytes: Long,
    val bufferBytes: Int,
    val idleTimeoutSeconds: Long,
    val tuiDetected: Boolean,
    val tuiHint: Boolean = false,
)

/** Logical failures reported by terminal-session operations. */
internal sealed interface TerminalSessionError {
    /**
     * The request violates the action contract.
     *
     * @property messages Actionable validation messages, accumulated so one turn can fix all of them.
     */
    data class InvalidInput(val messages: List<String>) : TerminalSessionError

    /**
     * The id does not resolve to a live session or a retained tombstone.
     *
     * @property sessionId Id that failed to resolve.
     */
    data class SessionNotFound(val sessionId: String) : TerminalSessionError

    /**
     * The session's process already exited, so input cannot be delivered.
     *
     * @property sessionId Id of the session whose process ended.
     * @property exitCode Recorded exit status, or null when it was unavailable.
     */
    data class SessionNotWritable(
        val sessionId: String,
        val exitCode: Int?,
    ) : TerminalSessionError

    /**
     * The session was closed; the snapshot carries any output not yet returned.
     *
     * @property sessionId Id of the closed session.
     * @property reason Why the session was closed.
     * @property snapshot Final undelivered output when a read was possible, otherwise null.
     */
    data class SessionClosed(
        val sessionId: String,
        val reason: TerminalCloseReason,
        val snapshot: TerminalSnapshot? = null,
    ) : TerminalSessionError

    /**
     * The live-session limit is already reached.
     *
     * @property detail Message naming the limit and the remedy.
     */
    data class SessionLimitReached(val detail: String) : TerminalSessionError

    /**
     * The platform could not start the process.
     *
     * @property detail Underlying failure description.
     */
    data class SpawnFailed(val detail: String) : TerminalSessionError
}

/**
 * Cheap state used by the manager's settle loop before it consumes output.
 *
 * @property status Status a consuming read would currently report.
 * @property pendingBytes UTF-8 bytes produced but not yet returned.
 * @property wouldTruncate Whether the per-call line or byte cap would cut the available backlog.
 * @property quietForMillis Milliseconds since output was last observed.
 * @property producedOutputVersion Output version used to detect output produced by an action.
 * @property alive Whether the process is still running.
 */
internal data class SessionPreview(
    val status: TerminalStatus,
    val pendingBytes: Int,
    val wouldTruncate: Boolean,
    val quietForMillis: Long,
    val producedOutputVersion: Long,
    val alive: Boolean,
)

/**
 * One PTY-backed terminal session.
 *
 * The reader drains the process continuously and appends sanitized text under [bufferLock]; every
 * action and the close transition are serialized by [actionLock]. Keeping the two locks separate is
 * what lets `close` join the reader without the reader needing the lock that `close` holds. Actions
 * suspend while they hold [actionLock], so that lock is a coroutine mutex rather than a thread-owned
 * JVM lock, and [bufferLock] is only ever held by non-suspending blocks.
 *
 * @param id Six-character session handle generated by the manager.
 * @param process Started PTY process; the session owns its streams.
 * @param terminator Strategy terminating the process tree for the resolved isolation state.
 * @param buffer Bounded sanitized output storage.
 * @param sanitizer Stateful control-sequence filter run on the reader thread.
 * @param config Terminal-session limits applied to this process.
 * @param idleTimeoutSeconds Resolved idle TTL for this session.
 * @param clock Monotonic nanosecond clock, injectable for deterministic tests.
 * @param logger Receives lifecycle and leak warnings.
 */
internal class TerminalSession(
    val id: String,
    private val process: Process,
    private val terminator: CommandTerminator,
    private val buffer: OutputRingBuffer,
    private val sanitizer: TerminalOutputSanitizer,
    private val config: TerminalSessionsConfig,
    val idleTimeoutSeconds: Long,
    private val clock: () -> Long = System::nanoTime,
    private val logger: Logger,
) {

    /**
     * Serializes actions and the close transition for this session.
     *
     * A coroutine mutex is owned by the logical action rather than by a thread, so an action may
     * suspend inside the critical section and resume on any thread without stranding the lock.
     */
    internal val actionLock = Mutex()

    /** Guards the buffer, cursor, lifecycle, exit code and quiet timers. */
    private val bufferLock = Any()

    /** Absolute character offset of the next unread character. */
    private var cursor: Long = 0L

    /** UTF-8 byte offset of [cursor], so drop notices can report bytes after eviction. */
    private var cursorBytes: Long = 0L

    /** Monotonic creation timestamp used for the absolute-lifetime check. */
    private val createdNanos: Long = clock()

    /** Current stored lifecycle state, which is only ever promoted towards `CLOSED`. */
    private var lifecycle: TerminalLifecycle = TerminalLifecycle.ALIVE

    /** Reason recorded when the session closes, or null while it is not closed. */
    private var closedReasonValue: TerminalCloseReason? = null

    /** Monotonic close timestamp, or null while the session is not closed. */
    private var closedAtNanosValue: Long? = null

    /** Recorded process exit status, or null while it is unknown. */
    private var exitCodeValue: Int? = null

    /**
     * Monotonic time the process was first observed dead, or null while it is alive or unreported.
     *
     * It bounds how long a dead process may stay unreported while the reader is still draining.
     */
    private var processExitNanosValue: Long? = null

    /** Monotonic time of the most recent output or action, used for idle reaping. */
    private var lastActivityNanos: Long = clock()

    /** Monotonic time output was last observed, used for the quiescence rule. */
    private var lastOutputNanos: Long = clock()

    /** Incremented whenever non-empty sanitized output is appended. */
    private var outputVersionValue: Long = 0L

    /** True once a close transition has started, so new actions are rejected. */
    private var closing = false

    /** True once the one-time full-screen-UI hint has been returned. */
    private var tuiHintEmitted = false

    /** Guards against starting the reader thread more than once. */
    private val readerStarted = AtomicBoolean(false)

    /** Daemon thread that keeps the PTY drained until EOF or close. */
    private val readerThread: Thread = Thread(::readLoop, "terminal-session-reader-$id").apply {
        isDaemon = true
    }

    init {
        readerThread.start()
        readerStarted.set(true)
    }

    /** Whether the session has been finalized as closed. */
    val isClosed: Boolean
        get() = synchronized(bufferLock) { lifecycle == TerminalLifecycle.CLOSED }

    /** Reason recorded when the session was closed, or null while it is not closed. */
    val closedReason: TerminalCloseReason?
        get() = synchronized(bufferLock) { closedReasonValue }

    /** Monotonic close timestamp, or null while the session is not closed. */
    val closedAtNanos: Long?
        get() = synchronized(bufferLock) { closedAtNanosValue }

    /** Recorded exit code, or null while the process has not ended or the code is unavailable. */
    val exitCode: Int?
        get() = synchronized(bufferLock) { exitCodeValue }

    /** Nanoseconds of the most recent output or action. */
    val lastActivityNanosSnapshot: Long
        get() = synchronized(bufferLock) { lastActivityNanos }

    /** Monotonic creation timestamp used for the absolute-lifetime check. */
    val createdAtNanos: Long
        get() = createdNanos

    /**
     * Records an action so an actively polled quiet session is not reaped.
     */
    fun recordActivity() {
        synchronized(bufferLock) {
            lastActivityNanos = clock()
        }
    }

    /** Whether input can still be delivered to the process. */
    fun isWritable(): Boolean = synchronized(bufferLock) {
        lifecycle == TerminalLifecycle.ALIVE && !closing && process.isAlive
    }

    /**
     * Writes input to the process verbatim.
     *
     * @param input Text to deliver; empty input is rejected before this call.
     * @throws IOException When the process no longer accepts input.
     */
    @Throws(IOException::class)
    fun writeInput(input: String) {
        val stream: OutputStream = process.outputStream
        stream.write(input.toByteArray(Charsets.UTF_8))
        stream.flush()
        recordActivity()
    }

    /**
     * Inspects state without consuming output.
     *
     * @param maxLines Maximum lines a consuming read would return.
     * @param maxBytes Maximum bytes a consuming read would return.
     * @return Status, pending byte count and whether a read would be truncated by the caps.
     */
    fun preview(maxLines: Int, maxBytes: Int): SessionPreview = synchronized(bufferLock) {
        val now = clock()
        val pendingBytes = pendingBytesLocked()
        val capped = buffer.read(cursor, maxBytes.coerceAtLeast(1), maxBytes.coerceAtLeast(1))
        val wouldTruncate = cutToLines(capped.text, maxLines).length < capped.text.length
        SessionPreview(
            status = statusLocked(pendingBytes, now),
            pendingBytes = pendingBytes,
            wouldTruncate = wouldTruncate,
            quietForMillis = quietForMillisLocked(now),
            producedOutputVersion = outputVersionValue,
            alive = lifecycle == TerminalLifecycle.ALIVE && process.isAlive,
        )
    }

    /**
     * Consumes the next output chunk and advances the session cursor past the consumed characters.
     *
     * @param maxLines Maximum complete lines returned.
     * @param maxBytes Maximum UTF-8 bytes returned.
     * @return The snapshot for this read; its status reflects the state after the read.
     */
    fun readSnapshot(maxLines: Int, maxBytes: Int): TerminalSnapshot = synchronized(bufferLock) {
        readSnapshotLocked(maxLines, maxBytes)
    }

    /** @return Output version observed at construction; used to detect output produced by an action. */
    fun currentOutputVersion(): Long = synchronized(bufferLock) { outputVersionValue }

    /**
     * Terminates the process, closes the PTY streams and joins the reader outside every lock.
     *
     * The action lock must not be held by the caller: the reader needs the buffer monitor while it
     * drains, and joining under the action lock would only time out.
     *
     * @param reason Reason recorded on the tombstone.
     * @param deadline Shared monotonic deadline for termination and the reader join.
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @param ioDispatcher Dispatcher used for the blocking termination path.
     * @param actionLockWaitNanos Maximum time to wait for the action lock; a still-running action
     *   never prevents termination, the process is then torn down without the lock.
     * @return The final snapshot, with status `CLOSED`.
     */
    suspend fun close(
        reason: TerminalCloseReason,
        deadline: Long,
        maxLines: Int,
        maxBytes: Int,
        ioDispatcher: CoroutineDispatcher,
        actionLockWaitNanos: Long,
    ): TerminalSnapshot {
        val transition = beginClose(actionLockWaitNanos)
        when (transition) {
            CloseTransition.BUSY -> {
                logger.warn(
                    "Terminal session {} still had a running action after {}ms; terminating its process",
                    id,
                    TimeUnit.NANOSECONDS.toMillis(actionLockWaitNanos),
                )
                return terminateWithoutActionLock(reason, deadline, maxLines, maxBytes, ioDispatcher)
            }
            CloseTransition.ALREADY_CLOSED ->
                return synchronized(bufferLock) { readSnapshotLocked(maxLines, maxBytes) }
            CloseTransition.ALREADY_CLOSING -> {
                // Another close owns termination. Wait for it to finalize so this call remains a
                // snapshot read rather than a second termination of the same process tree.
                while (!isClosed && System.nanoTime() < deadline) {
                    delay(CLOSE_WAIT_MILLIS.milliseconds)
                }
                return synchronized(bufferLock) { readSnapshotLocked(maxLines, maxBytes) }
            }
            CloseTransition.MARKED -> Unit
        }

        withContext(NonCancellable + ioDispatcher) { teardown(deadline) }

        return finishClose(actionLockWaitNanos, deadline, reason, maxLines, maxBytes)
    }

    /**
     * Acquires the action lock and records that this call owns the close transition.
     *
     * @param waitNanos Maximum action-lock wait, or [Long.MAX_VALUE] to wait indefinitely.
     * @return How the close transition ended.
     */
    private suspend fun beginClose(waitNanos: Long): CloseTransition {
        if (!acquireActionLock(waitNanos)) return CloseTransition.BUSY
        try {
            return synchronized(bufferLock) {
                when {
                    lifecycle == TerminalLifecycle.CLOSED -> CloseTransition.ALREADY_CLOSED
                    closing -> CloseTransition.ALREADY_CLOSING
                    else -> {
                        closing = true
                        CloseTransition.MARKED
                    }
                }
            }
        } finally {
            actionLock.unlock()
        }
    }

    /**
     * Re-acquires the action lock after teardown and finalizes the tombstone without overrunning the
     * caller's close budget.
     *
     * @param waitNanos Maximum action-lock wait, or [Long.MAX_VALUE] to wait indefinitely.
     * @param deadline Shared monotonic deadline for bounded callers.
     * @param reason Reason recorded on the tombstone.
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @return The final snapshot, with status `CLOSED`.
     */
    private suspend fun finishClose(
        waitNanos: Long,
        deadline: Long,
        reason: TerminalCloseReason,
        maxLines: Int,
        maxBytes: Int,
    ): TerminalSnapshot {
        // Another action may have slipped in during teardown; finalizing under the buffer monitor is
        // sufficient, so never wait beyond the caller's budget or the shared deadline.
        val budget = if (waitNanos == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            minOf(waitNanos, remainingNanos(deadline))
        }
        if (acquireActionLock(budget)) {
            try {
                return finalizeClose(reason, maxLines, maxBytes)
            } finally {
                actionLock.unlock()
            }
        }
        return finalizeClose(reason, maxLines, maxBytes)
    }

    /**
     * Acquires [actionLock] within a bounded budget.
     *
     * @param waitNanos Maximum wait in nanoseconds, or [Long.MAX_VALUE] to wait indefinitely.
     * @return True when this coroutine now holds the lock, false when the budget expired.
     */
    private suspend fun acquireActionLock(waitNanos: Long): Boolean {
        if (waitNanos == Long.MAX_VALUE) {
            actionLock.lock()
            return true
        }
        val budget = waitNanos.coerceAtLeast(0L)
        if (budget == 0L) return actionLock.tryLock()
        // The timeout cancels the pending acquisition, which never leaves the mutex acquired.
        return withTimeoutOrNull(budget.nanoseconds) { actionLock.lock() } != null
    }

    /**
     * Consumes the next chunk from an already-closed tombstone.
     *
     * Draining a tombstone is the same cursor-advancing read a live session performs, so the read is
     * implemented once and this name marks the closed-read call site.
     *
     * @param maxLines Maximum lines returned.
     * @param maxBytes Maximum bytes returned.
     * @return Snapshot with status `CLOSED`.
     */
    fun readClosedSnapshot(maxLines: Int, maxBytes: Int): TerminalSnapshot =
        readSnapshot(maxLines, maxBytes)

    /**
     * Terminates the process when no action lock is available, then finalizes the session.
     *
     * A shutdown path uses this after its bounded lock wait expires; marking the session CLOSED
     * follows real teardown, so the tombstone never claims a live child was stopped.
     *
     * @param reason Reason recorded on the tombstone.
     * @param deadline Shared monotonic deadline for termination and the reader join.
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @param ioDispatcher Dispatcher used for the blocking termination path.
     * @return The final snapshot, with status `CLOSED`.
     */
    private suspend fun terminateWithoutActionLock(
        reason: TerminalCloseReason,
        deadline: Long,
        maxLines: Int,
        maxBytes: Int,
        ioDispatcher: CoroutineDispatcher,
    ): TerminalSnapshot {
        synchronized(bufferLock) { closing = true }
        withContext(NonCancellable + ioDispatcher) { teardown(deadline) }
        return finalizeClose(reason, maxLines, maxBytes)
    }

    /**
     * Marks the session CLOSED and returns the final snapshot after teardown has completed.
     *
     * @param reason Reason recorded on the tombstone.
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @return The final snapshot, with status `CLOSED`.
     */
    private fun finalizeClose(
        reason: TerminalCloseReason,
        maxLines: Int,
        maxBytes: Int,
    ): TerminalSnapshot = synchronized(bufferLock) {
        recordExitLocked()
        if (lifecycle != TerminalLifecycle.CLOSED) {
            lifecycle = TerminalLifecycle.CLOSED
            closedReasonValue = reason
            closedAtNanosValue = clock()
        }
        readSnapshotLocked(maxLines, maxBytes)
    }

    /**
     * Signals the process tree, joins the reader, then closes both master streams.
     *
     * Both streams are views of the same PTY master, so closing either one ends the reader's view of
     * the device and discards the bytes the child wrote but nobody read yet; the join therefore runs
     * first so that tail still reaches the buffer. The join is bounded by [deadline], and the closes
     * afterwards unblock a reader that never observes EOF, so teardown cannot hang.
     *
     * @param deadline Shared monotonic deadline for termination and the reader join.
     */
    private fun teardown(deadline: Long) {
        try {
            terminator.terminate(TrackedCommand(process), deadline)
        } catch (e: Exception) {
            // Some PTY implementations cannot expose a ProcessHandle; fall back to the direct
            // handle so close still ends the child instead of leaving it alive.
            logger.warn("Terminal session {} termination was not confirmed: {}", id, e.message)
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
     * Drains the PTY until EOF or close.
     *
     * Every read happens under the buffer monitor, never under the action lock, so a concurrent
     * `close` can join this thread after releasing the action lock.
     */
    private fun readLoop() {
        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                val chars = CharArray(READER_BUFFER_SIZE)
                while (true) {
                    val count = try {
                        reader.read(chars)
                    } catch (_: IOException) {
                        // A closed master stream ends the reader normally during session close.
                        -1
                    }
                    if (count < 0) break
                    val text = sanitizer.feed(chars, count)
                    val now = clock()
                    synchronized(bufferLock) {
                        if (text.isNotEmpty()) {
                            buffer.append(text)
                            lastOutputNanos = now
                            outputVersionValue++
                        }
                        lastActivityNanos = now
                    }
                }
            }
        } catch (e: Exception) {
            if (!isClosed) logger.warn("Terminal session {} reader failed: {}", id, e.message)
        } finally {
            val exit = determineExitCode()
            synchronized(bufferLock) {
                if (lifecycle == TerminalLifecycle.ALIVE) lifecycle = TerminalLifecycle.EXITED
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
            logger.warn("Terminal session {} reader did not stop during close", id)
        }
    }

    /**
     * Consumes one bounded chunk; the caller must hold [bufferLock].
     *
     * @param maxLines Maximum complete lines returned.
     * @param maxBytes Maximum UTF-8 bytes returned.
     * @return Snapshot for the consumed chunk.
     */
    private fun readSnapshotLocked(maxLines: Int, maxBytes: Int): TerminalSnapshot {
        val now = clock()
        val chunk = buffer.read(cursor, maxBytes.coerceAtLeast(1), maxBytes.coerceAtLeast(1))
        val cut = cutToLines(chunk.text, maxLines)
        // When the cursor fell behind the retained window, the chunk starts at oldestRetained.
        // Advance over the dropped region as well, or the next read would re-emit this window.
        val droppedBytes = if (chunk.droppedChars > 0) buffer.oldestRetainedBytes - cursorBytes else 0L
        cursor += chunk.droppedChars + cut.length
        cursorBytes += droppedBytes + cut.encodeToByteArray().size
        val pendingBytes = pendingBytesLocked()
        val showTuiHint = sanitizer.tuiDetected && !tuiHintEmitted
        if (showTuiHint) tuiHintEmitted = true
        val output = if (droppedBytes > 0) {
            droppedNotice(droppedBytes) + cut
        } else {
            cut
        }
        return TerminalSnapshot(
            sessionId = id,
            status = statusLocked(pendingBytes, now),
            output = output,
            exitCode = exitCodeValue,
            hasNewOutput = output.isNotEmpty(),
            quietForMillis = quietForMillisLocked(now),
            truncated = pendingBytes > 0,
            pendingBytes = pendingBytes,
            droppedBytes = droppedBytes,
            bufferBytes = buffer.retainedBytes,
            idleTimeoutSeconds = idleTimeoutSeconds,
            tuiDetected = sanitizer.tuiDetected,
            tuiHint = showTuiHint,
        )
    }

    /**
     * @param pendingBytes Bytes still pending after the current cursor.
     * @param now Current monotonic time.
     * @return Status derived from the drained lifecycle, pending output and quiet time.
     */
    private fun statusLocked(pendingBytes: Int, now: Long): TerminalStatus {
        if (lifecycle == TerminalLifecycle.CLOSED) return TerminalStatus.CLOSED
        recordExitLocked()
        if (exitReportableLocked(now)) return TerminalStatus.EXITED
        // A dead process whose reader has not drained yet still has bytes in flight: report it as
        // running so the caller waits for them instead of seeing an ended session with no tail.
        if (processExitNanosValue != null) return TerminalStatus.RUNNING
        if (pendingBytes > 0) return TerminalStatus.RUNNING
        return if (quietForMillisLocked(now) >= config.quietMillis) TerminalStatus.QUIET else TerminalStatus.RUNNING
    }

    /**
     * Whether the session may be reported as exited.
     *
     * The drain is complete only when the reader reached EOF, because the child's last bytes enter
     * the buffer there. The gate is bounded by [READER_DRAIN_GRACE_NANOS] so a reader that never
     * observes EOF cannot keep the session from reporting its exit forever.
     *
     * @param now Current monotonic time.
     * @return True when the reader drained or the drain grace after process exit has elapsed.
     */
    private fun exitReportableLocked(now: Long): Boolean {
        if (lifecycle == TerminalLifecycle.EXITED) return true
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

    /** Outcome of the short action-lock transition that starts a close. */
    private enum class CloseTransition {
        /** The action lock could not be acquired within the caller's budget. */
        BUSY,

        /** The session was already fully closed. */
        ALREADY_CLOSED,

        /** Another close already owns termination. */
        ALREADY_CLOSING,

        /** This call now owns termination. */
        MARKED,
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

        /** Poll interval while waiting for a concurrent close to finalize. */
        const val CLOSE_WAIT_MILLIS = 10L

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
