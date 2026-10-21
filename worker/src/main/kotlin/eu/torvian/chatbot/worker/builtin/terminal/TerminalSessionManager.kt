package eu.torvian.chatbot.worker.builtin.terminal

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.builtin.command.ChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.builtin.command.TrackedCommand
import eu.torvian.chatbot.worker.builtin.command.remainingNanos
import eu.torvian.chatbot.worker.config.TerminalSessionsConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.nio.file.Path
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns every live and recently closed terminal session of this worker process.
 *
 * Sessions are keyed by a random six-character token over `a-z`, `A-Z` and `1-9`. The id is a
 * capability-style handle for logs and debugging, not an authorization token: a stale id after a
 * worker restart resolves to nothing, and guessing another conversation's session is impractical.
 * All actions are serialized per session by a coroutine mutex; the manager lock only protects the
 * registry, slot accounting and tombstone order.
 *
 * @param config Terminal-session limits and timing.
 * @param resolution Resolved isolation state used for launcher, environment and terminator.
 * @param childEnvironment Provider of the complete child environment.
 * @param workspace Working directory for every session child.
 * @param processFactory Starts the PTY process; tests substitute an ordinary process.
 * @param clock Monotonic nanosecond clock used for reaping and settle decisions.
 * @param idGenerator Session-id generator seam; tests use it to force collisions.
 * @param ioDispatcher Dispatcher for blocking spawn, write, terminate and join work.
 * @param shutdownGraceMillis Shared budget for closing every session at shutdown.
 * @param logger Receives lifecycle, limit and leak warnings.
 */
internal class TerminalSessionManager(
    internal val config: TerminalSessionsConfig,
    private val resolution: CommandIsolationResolution,
    private val childEnvironment: ChildEnvironmentProvider,
    private val workspace: Path,
    private val processFactory: TerminalProcessFactory = Pty4jTerminalProcessFactory(),
    private val clock: () -> Long = System::nanoTime,
    private val idGenerator: () -> String = { randomSessionToken() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val shutdownGraceMillis: Long = SHUTDOWN_GRACE_MILLIS,
    private val logger: Logger,
) : AutoCloseable {

    private val registry = ConcurrentHashMap<String, TerminalSession>()
    private val managerLock = Any()
    private val pendingIds = mutableSetOf<String>()
    private val closedOrder = ArrayDeque<String>()
    private var liveCount = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reaperJob: Job = scope.launch { runReaper() }
    private val closed = AtomicBoolean(false)

    /**
     * Starts a new session.
     *
     * A live-session slot is reserved before the PTY is spawned, so two concurrent starts cannot
     * both pass the limit check; the slot is released if the spawn fails.
     *
     * @param command Executable requested by the caller.
     * @param args Arguments for the executable.
     * @param idleTimeoutSeconds Requested idle TTL, capped by the configured maximum.
     * @param waitMillis Maximum time this call waits for output to settle.
     * @param maxLines Maximum lines returned by this call.
     * @param maxBytes Maximum bytes returned by this call.
     * @return Session snapshot or a logical failure.
     */
    suspend fun start(
        command: String,
        args: List<String>,
        idleTimeoutSeconds: Long?,
        waitMillis: Long,
        maxLines: Int,
        maxBytes: Int,
    ): Either<TerminalSessionError, TerminalSnapshot> = either {
        synchronized(managerLock) {
            ensure(liveCount < config.maxSessions) {
                TerminalSessionError.SessionLimitReached(
                    "The worker already has $liveCount of ${config.maxSessions} live terminal sessions. " +
                        "Close an existing session or raise worker.terminalSessions.maxSessions."
                )
            }
            liveCount++
        }

        // Track the reservation independently of the id, so id-allocation failure also releases it.
        var slotReserved = true
        var registeredId: String? = null
        var spawnedProcess: Process? = null
        try {
            val sessionId = reserveId()
            registeredId = sessionId
            val spec = TerminalProcessSpec(
                argv = resolution.launcher.wrap(command, args),
                environment = terminalEnvironment(childEnvironment.build()),
                workingDirectory = workspace,
                columns = config.columns,
                rows = config.rows,
            )
            val process = try {
                // Record the handle inside the same block: a cancellation after the factory returns
                // must still reach the teardown path in `finally`.
                withContext(ioDispatcher) { processFactory.start(spec).also { spawnedProcess = it } }
            } catch (e: IOException) {
                raise(TerminalSessionError.SpawnFailed("Failed to start terminal process: ${e.message}"))
            }
            val session = TerminalSession(
                id = sessionId,
                process = process,
                terminator = resolution.terminator,
                buffer = OutputRingBuffer(config.maxOutputBytesPerSession),
                sanitizer = TerminalOutputSanitizer(config.tuiEscapeRatioThreshold),
                config = config,
                idleTimeoutSeconds = resolveIdleTimeout(idleTimeoutSeconds),
                clock = clock,
                logger = logger,
            )
            synchronized(managerLock) {
                pendingIds.remove(sessionId)
                registry[sessionId] = session
            }
            slotReserved = false
            logger.info("Terminal session {} started; isolated account={}", sessionId, resolution.isolatedUser ?: "worker")
            try {
                awaitSnapshot(
                    session = session,
                    // Any output is new for a freshly spawned session, so the baseline is zero; a
                    // pre-sampled version would make the settle rule miss the prompt already drained
                    // by the reader and wait out the deadline.
                    startOutputVersion = 0L,
                    waitMillis = waitMillis,
                    maxLines = maxLines,
                    maxBytes = maxBytes,
                    requireOutputSinceStart = true,
                )
            } catch (cancellation: CancellationException) {
                // Cancellation is only observable at a suspension point, and every one after
                // registration is inside this block. The caller never learns the id, so nobody else
                // can close this session: tear it down here or its PTY and slot outlive the call.
                withContext(NonCancellable) { discardCancelledStart(session) }
                throw cancellation
            }
        } finally {
            val id = registeredId
            if (slotReserved) {
                synchronized(managerLock) {
                    pendingIds.remove(id)
                    liveCount = (liveCount - 1).coerceAtLeast(0)
                }
                spawnedProcess?.let { process ->
                    runCatching {
                        withContext(NonCancellable + ioDispatcher) {
                            terminalTerminate(process)
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes input to a live session and waits for output to settle.
     *
     * @param sessionId Session handle returned by [start].
     * @param input Verbatim text to write; the tool rejects empty input.
     * @param waitMillis Maximum time this call waits for output to settle.
     * @param maxLines Maximum lines returned by this call.
     * @param maxBytes Maximum bytes returned by this call.
     * @return Session snapshot or a logical failure.
     */
    suspend fun send(
        sessionId: String,
        input: String,
        waitMillis: Long,
        maxLines: Int,
        maxBytes: Int,
    ): Either<TerminalSessionError, TerminalSnapshot> = either {
        val session = lookup(sessionId)
        session.actionLock.withLock {
            ensure(!session.isClosed) {
                TerminalSessionError.SessionClosed(
                    sessionId,
                    session.closedReason ?: TerminalCloseReason.REQUESTED,
                    session.readClosedSnapshot(maxLines, maxBytes),
                )
            }
            ensure(session.isWritable()) {
                TerminalSessionError.SessionNotWritable(sessionId, session.exitCode)
            }
            val startVersion = session.currentOutputVersion()
            session.recordActivity()
            try {
                withContext(ioDispatcher) { session.writeInput(input) }
            } catch (_: IOException) {
                raise(TerminalSessionError.SessionNotWritable(sessionId, session.exitCode))
            }
            awaitSnapshot(
                session = session,
                startOutputVersion = startVersion,
                waitMillis = waitMillis,
                maxLines = maxLines,
                maxBytes = maxBytes,
                requireOutputSinceStart = true,
            )
        }
    }

    /**
     * Reads output produced since the previous action without writing input.
     *
     * A closed tombstone keeps the output it never delivered, so reading it is a normal read: the
     * call returns the next bounded chunk with status `CLOSED`, and once nothing is left it returns
     * an empty `CLOSED` snapshot. Only a write is a failure on a closed session.
     *
     * @param sessionId Session handle returned by [start].
     * @param waitMillis Maximum time this call waits for data.
     * @param maxLines Maximum lines returned by this call.
     * @param maxBytes Maximum bytes returned by this call.
     * @return Session snapshot or a logical failure.
     */
    suspend fun poll(
        sessionId: String,
        waitMillis: Long,
        maxLines: Int,
        maxBytes: Int,
    ): Either<TerminalSessionError, TerminalSnapshot> = either {
        val session = lookup(sessionId)
        session.actionLock.withLock {
            // A closed session cannot produce more output, so there is nothing to settle: returning
            // the pending chunk (or an empty one) immediately is what makes a late poll cheap.
            if (session.isClosed) return@withLock session.readClosedSnapshot(maxLines, maxBytes)
            val startVersion = session.currentOutputVersion()
            session.recordActivity()
            awaitSnapshot(
                session = session,
                startOutputVersion = startVersion,
                waitMillis = waitMillis,
                maxLines = maxLines,
                maxBytes = maxBytes,
                requireOutputSinceStart = false,
            )
        }
    }

    /**
     * Closes a session and returns its final undelivered output.
     *
     * The action lock is held only to mark the transition; termination and the reader join happen
     * outside it, so the reader can keep appending to the buffer while the process is torn down. The
     * wait for a concurrent action is bounded, so a long poll cannot make this call hang.
     *
     * @param sessionId Session handle returned by [start].
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @return Final snapshot or a logical failure.
     */
    suspend fun close(
        sessionId: String,
        maxLines: Int,
        maxBytes: Int,
    ): Either<TerminalSessionError, TerminalSnapshot> = either {
        val session = lookup(sessionId)
        session.recordActivity()
        val deadline = deadlineAfterMillis(TERMINATION_GRACE_MILLIS)
        val snapshot = session.close(
            reason = TerminalCloseReason.REQUESTED,
            deadline = deadline,
            maxLines = maxLines,
            maxBytes = maxBytes,
            ioDispatcher = ioDispatcher,
            actionLockWaitNanos = remainingNanos(deadline),
        )
        archiveClosed(session)
        logger.info("Terminal session {} closed; reason={}", sessionId, TerminalCloseReason.REQUESTED)
        snapshot
    }

    /** Cancels the reaper and closes every live session with one shared deadline. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reaperJob.cancel()
        val sessions = synchronized(managerLock) {
            registry.values.filter { !it.isClosed }
        }
        logger.info("Closing {} live terminal session(s) on shutdown", sessions.size)
        val deadline = deadlineAfterMillis(shutdownGraceMillis)
        sessions.forEach { session ->
            runCatching {
                runBlocking(ioDispatcher) {
                    val snapshot = session.close(
                        reason = TerminalCloseReason.SHUTDOWN,
                        deadline = deadline,
                        maxLines = Int.MAX_VALUE,
                        maxBytes = Int.MAX_VALUE,
                        ioDispatcher = ioDispatcher,
                        actionLockWaitNanos = remainingNanos(deadline).coerceAtLeast(0L),
                    )
                    archiveClosed(session)
                    if (snapshot.status != TerminalStatus.CLOSED) {
                        logger.warn("Terminal session {} did not confirm shutdown", session.id)
                    }
                }
            }.onFailure { error ->
                logger.warn("Terminal session {} could not be closed on shutdown: {}", session.id, error.message)
            }
        }
        synchronized(managerLock) {
            registry.clear()
            pendingIds.clear()
            closedOrder.clear()
            liveCount = 0
        }
        scope.cancel()
    }

    /**
     * Runs one reaper sweep; exposed so tests can drive it with a deterministic clock.
     */
    internal suspend fun reapOnce() {
        val now = clock()
        val sessions = registry.values.toList()
        for (session in sessions) {
            if (session.isClosed) {
                evictExpiredTombstone(session, now)
                continue
            }
            val idleExpired =
                now - session.lastActivityNanosSnapshot >= TimeUnit.SECONDS.toNanos(session.idleTimeoutSeconds)
            val lifetimeExpired =
                now - session.createdAtNanos >= TimeUnit.SECONDS.toNanos(config.maxLifetimeSeconds)
            if (!idleExpired && !lifetimeExpired) continue
            // Skip a session whose action is running rather than queueing behind a long poll.
            if (!session.actionLock.tryLock()) continue
            val stillEligible = try {
                !session.isClosed
            } finally {
                session.actionLock.unlock()
            }
            if (!stillEligible) continue
            val reason = if (lifetimeExpired) {
                TerminalCloseReason.MAX_LIFETIME
            } else {
                TerminalCloseReason.IDLE_TIMEOUT
            }
            runCatching {
                val deadline = deadlineAfterMillis(TERMINATION_GRACE_MILLIS)
                session.close(
                    reason = reason,
                    deadline = deadline,
                    maxLines = Int.MAX_VALUE,
                    maxBytes = Int.MAX_VALUE,
                    ioDispatcher = ioDispatcher,
                    actionLockWaitNanos = remainingNanos(deadline),
                )
                archiveClosed(session)
                logger.warn("Terminal session {} reaped; reason={}", session.id, reason)
            }.onFailure { error ->
                logger.warn("Reaping terminal session {} failed: {}", session.id, error.message)
            }
        }
    }

    /**
     * Waits for output to settle according to the action contract.
     */
    private suspend fun awaitSnapshot(
        session: TerminalSession,
        startOutputVersion: Long,
        waitMillis: Long,
        maxLines: Int,
        maxBytes: Int,
        requireOutputSinceStart: Boolean,
    ): TerminalSnapshot {
        val deadline = deadlineAfterMillis(waitMillis)
        while (true) {
            val preview = session.preview(maxLines, maxBytes)
            val producedSinceStart = preview.producedOutputVersion > startOutputVersion
            if (preview.status == TerminalStatus.EXITED || preview.status == TerminalStatus.CLOSED) {
                return session.readSnapshot(maxLines, maxBytes)
            }
            // Return immediately when the per-call cap would cut the available backlog; waiting
            // longer would only grow the pending chunk the caller has already asked to bound.
            if (preview.pendingBytes > 0 &&
                (preview.wouldTruncate || preview.pendingBytes > maxBytes)
            ) {
                return session.readSnapshot(maxLines, maxBytes)
            }
            val relevantOutput = producedSinceStart ||
                (!requireOutputSinceStart && preview.pendingBytes > 0)
            if (relevantOutput && preview.quietForMillis >= config.quietMillis) {
                return session.readSnapshot(maxLines, maxBytes)
            }
            if (System.nanoTime() >= deadline) {
                return session.readSnapshot(maxLines, maxBytes)
            }
            delay(POLL_INTERVAL_MILLIS.milliseconds)
        }
    }

    /**
     * @param sessionId Id to resolve.
     * @return The session or a raised `SessionNotFound`.
     */
    private fun Raise<TerminalSessionError>.lookup(sessionId: String): TerminalSession =
        registry[sessionId] ?: raise(TerminalSessionError.SessionNotFound(sessionId))

    /**
     * Reserves a fresh id against live and tombstoned sessions.
     *
     * @return The reserved id.
     */
    private fun Raise<TerminalSessionError>.reserveId(): String {
        synchronized(managerLock) {
            repeat(MAX_ID_ATTEMPTS) {
                val candidate = idGenerator()
                if (registry[candidate] == null && candidate !in pendingIds) {
                    pendingIds.add(candidate)
                    return candidate
                }
            }
        }
        raise(
            TerminalSessionError.SpawnFailed(
                "Could not allocate a free terminal session id after $MAX_ID_ATTEMPTS attempts."
            )
        )
    }

    /**
     * @param requested Caller-requested idle timeout, if any.
     * @return The resolved idle TTL.
     */
    private fun resolveIdleTimeout(requested: Long?): Long =
        requested?.coerceAtMost(config.maxIdleTimeoutSeconds) ?: config.defaultIdleTimeoutSeconds

    /**
     * Records a closed session as a tombstone and enforces the archive cap.
     *
     * @param session Session that has reached `CLOSED`.
     */
    private fun archiveClosed(session: TerminalSession) {
        // A concurrent close may still be tearing down; never turn a live session into a tombstone.
        if (!session.isClosed) return
        synchronized(managerLock) {
            if (closedOrder.contains(session.id)) return
            liveCount = (liveCount - 1).coerceAtLeast(0)
            closedOrder.addLast(session.id)
            while (closedOrder.size > config.maxClosedSessions) {
                val evicted = closedOrder.removeFirst()
                registry.remove(evicted)
            }
        }
    }

    /**
     * Evicts one tombstone whose retention has expired.
     *
     * @param session Closed session to test.
     * @param now Current monotonic time.
     */
    private fun evictExpiredTombstone(session: TerminalSession, now: Long) {
        val closedAt = session.closedAtNanos ?: return
        if (now - closedAt < TimeUnit.SECONDS.toNanos(config.closedSessionRetentionSeconds)) return
        synchronized(managerLock) {
            closedOrder.remove(session.id)
            registry.remove(session.id)
        }
    }

    /** Periodically sweeps idle, lifetime-expired and retention-expired sessions. */
    private suspend fun runReaper() {
        while (scope.isActive) {
            delay(TimeUnit.SECONDS.toMillis(config.reaperIntervalSeconds).milliseconds)
            runCatching { reapOnce() }
                .onFailure { error -> logger.warn("Terminal session reaper sweep failed: {}", error.message) }
        }
    }

    /**
     * Terminates a process whose session was never registered.
     *
     * The isolation-aware terminator is reused rather than a root-only kill so descendants of the
     * cancelled spawn are signalled too. Both terminator strategies tolerate a process that already
     * exited: handle operations are best-effort and waits return immediately.
     *
     * @param process Started process to tear down.
     */
    private fun terminalTerminate(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching {
            resolution.terminator.terminate(
                TrackedCommand(process),
                deadlineAfterMillis(TERMINATION_GRACE_MILLIS),
            )
        }
        // A terminator that cannot address the process (for example a handle-less PTY child) still
        // must not leave it running.
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    /**
     * Tears down a session whose creating `start` call was cancelled after registration.
     *
     * Runs under a non-cancellable scope: the caller's scope is already cancelled, yet the PTY, the
     * reader thread and the reserved live-session slot still have to be released.
     *
     * @param session Session that was registered but never returned to a caller.
     */
    private suspend fun discardCancelledStart(session: TerminalSession) {
        runCatching {
            session.close(
                reason = TerminalCloseReason.CANCELLED,
                deadline = deadlineAfterMillis(TERMINATION_GRACE_MILLIS),
                maxLines = Int.MAX_VALUE,
                maxBytes = Int.MAX_VALUE,
                ioDispatcher = ioDispatcher,
                actionLockWaitNanos = TimeUnit.MILLISECONDS.toNanos(TERMINATION_GRACE_MILLIS),
            )
            archiveClosed(session)
        }.onFailure { error ->
            logger.warn("Terminal session {} could not be discarded after cancellation: {}", session.id, error.message)
        }
        logger.info("Terminal session {} discarded; its start call was cancelled", session.id)
    }

    /**
     * @param millis Non-negative duration in milliseconds.
     * @return Saturated monotonic deadline in nanoseconds.
     */
    private fun deadlineAfterMillis(millis: Long): Long {
        // Waits and termination budgets are wall-clock bounded; only TTL and quiescence use the
        // injectable clock, so a frozen test clock cannot make a per-call deadline never expire.
        val now = System.nanoTime()
        val duration = TimeUnit.MILLISECONDS.toNanos(millis)
        return if (duration > 0L && now > Long.MAX_VALUE - duration) Long.MAX_VALUE else now + duration
    }

    private companion object {
        /** Settle-loop poll interval. */
        const val POLL_INTERVAL_MILLIS = 25L

        /** Bounded grace for terminating one session. */
        const val TERMINATION_GRACE_MILLIS = 2_000L

        /** Shared grace for closing every session on worker shutdown. */
        const val SHUTDOWN_GRACE_MILLIS = 5_000L

        /** Attempts allowed to find an unused session id. */
        const val MAX_ID_ATTEMPTS = 16

        /** Characters allowed in a session token: `a-z`, `A-Z` and `1-9`. */
        const val SESSION_ID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"

        /** Length of a session token. */
        const val SESSION_ID_LENGTH = 6

        /** Shared secure source for production session tokens. */
        val secureRandom = SecureRandom()

        /**
         * @return A fresh six-character session token.
         */
        fun randomSessionToken(): String {
            val chars = CharArray(SESSION_ID_LENGTH) {
                SESSION_ID_ALPHABET[secureRandom.nextInt(SESSION_ID_ALPHABET.length)]
            }
            return String(chars)
        }
    }
}
