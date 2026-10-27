package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.worker.builtin.command.remainingNanos
import eu.torvian.chatbot.worker.builtin.terminal.secret.SecretMaterial
import eu.torvian.chatbot.worker.credential.CredentialProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/** Status reported to the model for one action. */
internal enum class TerminalStatus { RUNNING, QUIET, ATTACHED, EXITED, CLOSED }

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

    /** The attached profile exited while the shell was already gone, so the session ended. */
    PROFILE_EXITED,
}

/** Why a profile target stopped being the active target. */
internal enum class DetachReason {
    /** The caller asked to detach. */
    REQUESTED,

    /** The profile process exited on its own. */
    PROFILE_EXITED,

    /** The session is closing. */
    CLOSE,

    /** The session was reaped for idleness or lifetime. */
    REAPED,

    /** The worker is shutting down. */
    SHUTDOWN,
}

/** What a snapshot reports about a target change on the call that caused it. */
internal enum class AttachTransition {
    /** The call did not change the active target. */
    NONE,

    /** The call attached a credential profile. */
    ATTACHED,

    /** The call returned the session to its shell. */
    DETACHED,

    /** The profile exited on its own and the session returned to its shell. */
    PROFILE_EXITED,
}

/**
 * Snapshot returned by one terminal action.
 *
 * @property sessionId Identifies the session this snapshot belongs to.
 * @property status Derived status for this read.
 * @property output Text returned by this action, possibly prefixed by a dropped-output notice and by the
 *   unread tail of a profile target the call detached.
 * @property exitCode Recorded exit code of the shell when it ended.
 * @property hasNewOutput Whether this read returned any output.
 * @property quietForMillis Elapsed milliseconds since output was last observed.
 * @property truncated True exactly when [pendingBytes] is positive.
 * @property pendingBytes UTF-8 bytes produced but not yet returned.
 * @property droppedBytes UTF-8 bytes evicted before the returned text.
 * @property bufferBytes Sanitized bytes currently retained by the session buffer.
 * @property idleTimeoutSeconds Idle lifetime configured for this session.
 * @property tuiDetected Whether an alternate-screen or escape-dense stream was observed.
 * @property tuiHint Whether this read should show the one-time TUI warning.
 * @property attached Whether a credential profile is the active target.
 * @property attachProfile Name of the attached profile, or null when none is attached.
 * @property attachChannel Channel the attached profile uses, or null when none is attached.
 * @property attachExitCode Exit code of the profile reported by the call that observed its exit.
 * @property transition Target change caused by this call, or [AttachTransition.NONE].
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
    val attached: Boolean = false,
    val attachProfile: String? = null,
    val attachChannel: CredentialChannel? = null,
    val attachExitCode: Int? = null,
    val transition: AttachTransition = AttachTransition.NONE,
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

    /**
     * This worker has no credential account, so no profile can ever be attached.
     *
     * Reported separately from [CredentialUnavailable] because it is a deployment condition rather
     * than a failure of one profile, and it must never fall back to delivery under the worker's own
     * account.
     *
     * @property detail Message naming the setting that decides availability.
     */
    data class CredentialSupportUnavailable(val detail: String) : TerminalSessionError

    /**
     * A credential profile exists but its secret could not be decrypted or delivered.
     *
     * @property profile Name of the profile that could not be attached.
     * @property detail Reason naming the profile and reference, never a value.
     */
    data class CredentialUnavailable(val profile: String, val detail: String) : TerminalSessionError

    /**
     * The named credential profile is not assigned to this worker.
     *
     * @property profile Name the caller asked for.
     * @property known Names of the enabled profiles linked to the acting role, already filtered so the
     *   hint never discloses a profile the role could not attach.
     */
    data class UnknownProfile(val profile: String, val known: List<String>) : TerminalSessionError

    /**
     * The acting agent role may not attach the named credential profile.
     *
     * Returned before any decryption or delivery, so a rejected call learns nothing about the secret
     * and the profile is left untouched.
     *
     * @property profile Name of the profile that was refused.
     * @property detail Reason naming the acting role or its absence, never a secret reference.
     */
    data class ProfileNotAuthorized(val profile: String, val detail: String) : TerminalSessionError

    /**
     * The action requires an attached profile but none is active.
     *
     * @property sessionId Id of the session the action addressed.
     */
    data class NotAttached(val sessionId: String) : TerminalSessionError

    /**
     * The action requires a detached session but a profile is active.
     *
     * @property sessionId Id of the session the action addressed.
     * @property profile Name of the profile that is attached.
     */
    data class AlreadyAttached(val sessionId: String, val profile: String) : TerminalSessionError

    /**
     * The session is alive but not quiet enough to attach.
     *
     * @property sessionId Id of the session the action addressed.
     */
    data class NotQuiet(val sessionId: String) : TerminalSessionError
}

/**
 * Cheap state used by the manager's settle loop before it consumes output.
 *
 * @property status Status a consuming read would currently report.
 * @property pendingBytes UTF-8 bytes produced but not yet returned.
 * @property wouldTruncate Whether the per-call line or byte cap would cut the available backlog.
 * @property quietForMillis Milliseconds since output was last observed.
 * @property producedOutputVersion Output version used to detect output produced by an action.
 * @property alive Whether the active process is still running.
 * @property carriesTransitionOutput Whether a target change left output the next snapshot must return.
 */
internal data class SessionPreview(
    val status: TerminalStatus,
    val pendingBytes: Int,
    val wouldTruncate: Boolean,
    val quietForMillis: Long,
    val producedOutputVersion: Long,
    val alive: Boolean,
    val carriesTransitionOutput: Boolean = false,
)

/**
 * Profile and secret material of the target that is currently attached.
 *
 * @property profile Profile whose fixed command is running.
 * @property material Secret delivery state to release when the target stops.
 */
internal class AttachedProfile(
    val profile: CredentialProfile,
    val material: SecretMaterial,
)

/**
 * A profile process that was spawned but is not yet the active target.
 *
 * A close that loses the action-lock race tears the session down without the lock, so the process and
 * the written secret must be reachable from the session itself, not only from the attach call that is
 * still running.
 *
 * @property profile Name of the profile being attached.
 * @property terminate Stops the spawned process tree; supplied by whoever started it.
 * @property material Secret delivery state to release, or null before it was built.
 */
internal class PendingProfile(
    val profile: String,
    private val terminate: (deadline: Long) -> Unit,
    private val material: SecretMaterial?,
) {
    /**
     * Stops the process and releases the material; idempotent enough for repeated cleanup paths.
     *
     * Each step keeps at least the cleanup grace of its own, because the deadline this call inherits
     * may already belong to the preparation that preceded it.
     *
     * @param deadline Deadline inherited from the caller, possibly already expired.
     */
    fun close(deadline: Long) {
        runCatching { terminate(withCleanupGrace(deadline)) }
        runCatching { material?.close(withCleanupGrace(deadline)) }
    }
}

/**
 * One PTY-backed terminal session.
 *
 * The session is a container: it owns the shell target and, while a credential profile is attached,
 * the profile target that currently receives input and output. Every action and the close transition
 * are serialized by [actionLock]; the targets keep their own locks for their buffers, cursors and
 * process state, and are never asked for the session's lock, so a teardown can join a reader that is
 * parked on its own buffer.
 *
 * Quiescence and activity are derived from the active target rather than mirrored into session fields:
 * two readers writing one session-level clock would make an attached session either immortal or
 * reaped while a profile still works.
 *
 * @param id Six-character session handle generated by the manager.
 * @param shell Target of the shell process that `start` created.
 * @param idleTimeoutSeconds Resolved idle TTL for this session.
 * @param clock Monotonic nanosecond clock, injectable for deterministic tests.
 * @param logger Receives lifecycle and leak warnings.
 */
internal class TerminalSession(
    val id: String,
    internal val shell: SessionTarget,
    val idleTimeoutSeconds: Long,
    private val clock: () -> Long = System::nanoTime,
    private val logger: Logger,
) {

    /**
     * Serializes actions, attach/detach changes and the close transition for this session.
     *
     * A coroutine mutex is owned by the logical action rather than by a thread, so an action may
     * suspend inside the critical section and resume on any thread without stranding the lock.
     */
    internal val actionLock = Mutex()

    /** Guards the session lifecycle, the active target and the one-time hints. */
    private val stateLock = Any()

    /** Target that currently receives input and produces the session's output. */
    private var active: SessionTarget = shell

    /** Profile currently attached, or null while the shell is active. */
    private var attached: AttachedProfile? = null

    /** Target of the attached profile, or null while the shell is active. */
    private var attachedTarget: SessionTarget? = null

    /** Profile process spawned but not yet registered, reclaimed by a close that wins the race. */
    private var pendingProfile: PendingProfile? = null

    /** Exit code of the last profile that stopped, reported once on the transition snapshot. */
    private var attachExitCodeValue: Int? = null

    /** Target change the next snapshot must report, cleared once it is reported. */
    private var pendingTransition: AttachTransition = AttachTransition.NONE

    /**
     * Unread output of the profile target a detach stopped, returned by the next snapshot.
     *
     * The profile owns its own cursor, and the target is dropped when the shell becomes active again,
     * so this text is unreachable once [detach] returns; it is carried here so the transition snapshot
     * can report it.
     */
    private var pendingTransitionOutput: String = ""

    /** Monotonic creation timestamp used for the absolute-lifetime check. */
    private val createdNanos: Long = clock()

    /** Current stored lifecycle state, which is only ever promoted towards `CLOSED`. */
    private var lifecycle: TerminalLifecycle = TerminalLifecycle.ALIVE

    /** Reason recorded when the session closes, or null while it is not closed. */
    private var closedReasonValue: TerminalCloseReason? = null

    /** Monotonic close timestamp, or null while the session is not closed. */
    private var closedAtNanosValue: Long? = null

    /** Monotonic time of the most recent action, used together with target output for idleness. */
    private var lastActionNanos: Long = clock()

    /** True once a close transition has started, so new actions are rejected. */
    private var closing = false

    /** True once the one-time full-screen-UI hint has been returned. */
    private var tuiHintEmitted = false

    /** Whether the session has been finalized as closed. */
    val isClosed: Boolean
        get() = synchronized(stateLock) { lifecycle == TerminalLifecycle.CLOSED }

    /** Reason recorded when the session was closed, or null while it is not closed. */
    val closedReason: TerminalCloseReason?
        get() = synchronized(stateLock) { closedReasonValue }

    /** Monotonic close timestamp, or null while the session is not closed. */
    val closedAtNanos: Long?
        get() = synchronized(stateLock) { closedAtNanosValue }

    /** Recorded exit code of the shell, or null while it has not ended or the code is unavailable. */
    val exitCode: Int?
        get() = shell.exitCode

    /** Name of the attached profile, or null while the shell is active. */
    val attachedProfileName: String?
        get() = synchronized(stateLock) { attached?.profile?.name }

    /** Whether a credential profile is currently the active target. */
    val isAttached: Boolean
        get() = synchronized(stateLock) { attached != null }

    /**
     * Nanoseconds of the most recent activity, derived from the last action and the active target's
     * output so an attached profile keeps its session alive without a second clock.
     */
    val lastActivityNanosSnapshot: Long
        get() = synchronized(stateLock) {
            maxOf(lastActionNanos, active.lastOutputNanosSnapshot)
        }

    /** Monotonic creation timestamp used for the absolute-lifetime check. */
    val createdAtNanos: Long
        get() = createdNanos

    /**
     * Records an action so an actively polled quiet session is not reaped.
     */
    fun recordActivity() {
        synchronized(stateLock) {
            lastActionNanos = clock()
        }
    }

    /** Whether input can still be delivered to the active target. */
    fun isWritable(): Boolean = synchronized(stateLock) {
        lifecycle == TerminalLifecycle.ALIVE && !closing && active.alive
    }

    /** Whether the shell process has ended and its reader has drained. */
    fun shellHasExited(): Boolean = shell.exited

    /** Whether the attached profile's process has ended and its reader has drained. */
    fun attachedProfileHasExited(): Boolean =
        synchronized(stateLock) { attachedTarget?.exited ?: false }

    /**
     * Whether the session is quiet enough to attach a credential profile.
     *
     * A shell that is still running a command would otherwise buffer input the model sends after a
     * detach and hand it to an unexpected process later.
     */
    fun isQuietEnoughToAttach(): Boolean {
        val preview = preview(Int.MAX_VALUE, Int.MAX_VALUE)
        return preview.status == TerminalStatus.QUIET
    }

    /**
     * Publishes the profile process a concurrent close must reclaim.
     *
     * @param pending Process and material of an attach that has not registered its target yet.
     */
    fun stageProfile(pending: PendingProfile) {
        synchronized(stateLock) {
            // Replacing an unclaimed staged profile would orphan it; the caller registers or closes
            // within one action, so this only guards against a programming error.
            check(pendingProfile == null) { "a profile attach is already staged for session $id" }
            pendingProfile = pending
        }
    }

    /** Clears the staged profile after it was registered or reclaimed by the caller. */
    fun clearStagedProfile() {
        synchronized(stateLock) { pendingProfile = null }
    }

    /**
     * Makes [profileTarget] the active target and suspends the shell.
     *
     * The caller holds [actionLock] and has already validated that the session is quiet and detached.
     *
     * @param profile Profile that produced the target, kept for `details` and cleanup.
     * @param profileTarget Spawned profile process with its own buffer.
     * @param material Secret delivery state the profile target depends on.
     * @return True when the profile became the active target; false when the session closed while the
     *   target was being spawned, in which case nothing was attached and the caller must clean up.
     * @pre The session is [TerminalStatus.QUIET] and has no active profile.
     */
    fun attach(profile: CredentialProfile, profileTarget: SessionTarget, material: SecretMaterial): Boolean {
        synchronized(stateLock) {
            if (lifecycle != TerminalLifecycle.ALIVE || closing) {
                pendingProfile = null
                return false
            }
            attached = AttachedProfile(profile = profile, material = material)
            attachedTarget = profileTarget
            active = profileTarget
            pendingTransition = AttachTransition.ATTACHED
            attachExitCodeValue = null
            pendingProfile = null
            recordActivityLocked()
        }
        logger.info("Terminal session {} attached profile {}", id, profile.name)
        return true
    }

    /**
     * Terminates the active profile target and makes the shell active again.
     *
     * The caller holds [actionLock] and wraps this call in a blocking-capable dispatcher: teardown
     * signals the process tree and joins the profile reader.
     *
     * The profile's unread output is read once its reader stopped and before the shell becomes active,
     * so a command that printed a diagnostic and then exited still reports it: the tail is what the
     * next snapshot returns with the transition.
     *
     * @param reason Why the profile stopped; recorded in the snapshot transition.
     * @param deadline Deadline inherited from the caller; each cleanup step is still given at least the
     *   cleanup grace, so a teardown that used the whole budget cannot leave the secret tree behind.
     * @param maxLines Maximum lines of the profile's unread tail carried to the next snapshot.
     * @param maxBytes Maximum bytes of the profile's unread tail carried to the next snapshot.
     * @return The profile exit code, or null when it could not be collected.
     */
    fun detach(reason: DetachReason, deadline: Long, maxLines: Int, maxBytes: Int): Int? {
        val target: SessionTarget
        val profile: AttachedProfile
        synchronized(stateLock) {
            target = attachedTarget ?: return null
            profile = attached ?: return null
        }

        target.teardown(withCleanupGrace(deadline))
        val exitCode = target.exitCode

        var unreportedBytes = 0
        synchronized(stateLock) {
            // A close may have finalized the session while the profile was being torn down; it owns
            // the tombstone and the material from that point on.
            if (lifecycle == TerminalLifecycle.CLOSED) return exitCode
            // The reader of the torn-down target has stopped, so this one bounded read is the whole
            // remaining tail. It runs before the switch: the cursor belongs to the profile target,
            // which nothing can address once the shell is active again.
            val tail = target.read(maxLines, maxBytes)
            unreportedBytes = tail.pendingBytes
            attached = null
            attachedTarget = null
            active = shell
            pendingTransition = when (reason) {
                DetachReason.PROFILE_EXITED -> AttachTransition.PROFILE_EXITED
                else -> AttachTransition.DETACHED
            }
            pendingTransitionOutput = tail.text
            attachExitCodeValue = exitCode
            recordActivityLocked()
        }
        runCatching { profile.material.close(withCleanupGrace(deadline)) }
        if (unreportedBytes > 0) {
            // The per-call cap cut the tail and the target is gone, so this asks the operator to raise
            // the limits rather than letting the loss stay invisible.
            logger.warn(
                "Terminal session {} dropped {} unread bytes of profile {} at the per-call output cap",
                id,
                unreportedBytes,
                profile.profile.name
            )
        }
        logger.info(
            "Terminal session {} detached profile {}; reason={} exitCode={}",
            id,
            profile.profile.name,
            reason,
            exitCode
        )
        return exitCode
    }

    /**
     * Writes input to the active target verbatim.
     *
     * @param input Text to deliver; empty input is rejected before this call.
     * @throws IOException When the process no longer accepts input.
     */
    @Throws(IOException::class)
    fun writeInput(input: String) {
        val target = synchronized(stateLock) { active }
        target.write(input)
        recordActivity()
    }

    /**
     * Inspects state without consuming output.
     *
     * @param maxLines Maximum lines a consuming read would return.
     * @param maxBytes Maximum bytes a consuming read would return.
     * @return Status, pending byte count and whether a read would be truncated by the caps.
     */
    fun preview(maxLines: Int, maxBytes: Int): SessionPreview {
        val target = synchronized(stateLock) { active }
        val preview = target.preview(maxLines, maxBytes)
        val (status, carriesTransitionOutput) = synchronized(stateLock) {
            statusOf(target, preview.status) to pendingTransitionOutput.isNotEmpty()
        }
        return SessionPreview(
            status = status,
            pendingBytes = preview.pendingBytes,
            wouldTruncate = preview.wouldTruncate,
            quietForMillis = preview.quietForMillis,
            producedOutputVersion = preview.producedOutputVersion,
            alive = preview.alive,
            carriesTransitionOutput = carriesTransitionOutput,
        )
    }

    /**
     * Consumes the next output chunk of the active target and advances its cursor.
     *
     * @param maxLines Maximum complete lines returned.
     * @param maxBytes Maximum UTF-8 bytes returned.
     * @return The snapshot for this read; its status reflects the state after the read.
     */
    fun readSnapshot(maxLines: Int, maxBytes: Int): TerminalSnapshot {
        // The tail a detach carried is returned by this snapshot alone, so it is spent from the same
        // budget rather than added on top of it; whatever the shell has beyond that budget stays
        // pending for the next action, because its cursor only advances over what is returned here.
        val (target, carried) = synchronized(stateLock) { active to pendingTransitionOutput }
        val carriedBytes = if (carried.isEmpty()) 0 else carried.encodeToByteArray().size
        val shellLines = if (carriedBytes >= maxBytes) 0 else maxLines - carried.count { it == '\n' }
        val read = target.read(
            maxLines = shellLines.coerceAtLeast(0),
            maxBytes = (maxBytes - carriedBytes).coerceAtLeast(1),
        )
        val (status, showTuiHint, attachState) = synchronized(stateLock) {
            val hint = read.tuiDetected && !tuiHintEmitted
            if (hint) tuiHintEmitted = true
            Triple(
                statusOf(target, read.status),
                hint,
                AttachSnapshotState(
                    attached = attached?.profile,
                    transition = pendingTransition,
                    exitCode = attachExitCodeValue,
                )
            )
        }
        // A transition is reported exactly once: the call that changed the target, together with the
        // tail it carried.
        if (attachState.transition != AttachTransition.NONE) {
            synchronized(stateLock) {
                if (pendingTransition == attachState.transition) {
                    pendingTransition = AttachTransition.NONE
                    pendingTransitionOutput = ""
                }
            }
        }
        // The carried tail travels with its transition, so a snapshot reports the two together or not
        // at all.
        val output = if (attachState.transition == AttachTransition.NONE) read.text else carried + read.text
        return TerminalSnapshot(
            sessionId = id,
            status = status,
            output = output,
            exitCode = read.exitCode,
            hasNewOutput = output.isNotEmpty(),
            quietForMillis = read.quietForMillis,
            truncated = read.pendingBytes > 0,
            pendingBytes = read.pendingBytes,
            droppedBytes = read.droppedBytes,
            bufferBytes = read.bufferBytes,
            idleTimeoutSeconds = idleTimeoutSeconds,
            tuiDetected = read.tuiDetected,
            tuiHint = showTuiHint,
            attached = attachState.attached != null,
            attachProfile = attachState.attached?.name,
            attachChannel = attachState.attached?.channel,
            attachExitCode = if (attachState.transition == AttachTransition.PROFILE_EXITED) {
                attachState.exitCode
            } else {
                null
            },
            transition = attachState.transition,
        )
    }

    /** @return Output version observed at construction; used to detect output produced by an action. */
    fun currentOutputVersion(): Long = synchronized(stateLock) { active.outputVersion }

    /**
     * Terminates the session's processes, closes the PTY streams and joins the readers outside every
     * lock.
     *
     * The action lock must not be held by the caller: the readers need their buffer monitors while
     * they drain, and joining under the action lock would only time out.
     *
     * @param reason Reason recorded on the tombstone.
     * @param deadline Deadline inherited from the caller; teardown still gives each step at least the
     *   cleanup grace, so an already-expired deadline cannot skip a termination or a secret removal.
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
                return readSnapshot(maxLines, maxBytes)
            CloseTransition.ALREADY_CLOSING -> {
                // Another close owns termination. Wait for it to finalize so this call remains a
                // snapshot read rather than a second termination of the same process tree.
                while (!isClosed && System.nanoTime() < deadline) {
                    delay(CLOSE_WAIT_MILLIS.milliseconds)
                }
                return readSnapshot(maxLines, maxBytes)
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
            return synchronized(stateLock) {
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
        // Another action may have slipped in during teardown; finalizing under the session lock is
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
     * Terminates the session when no action lock is available, then finalizes it.
     *
     * A shutdown path uses this after its bounded lock wait expires; marking the session CLOSED
     * follows real teardown, so the tombstone never claims a live child was stopped.
     *
     * @param reason Reason recorded on the tombstone.
     * @param deadline Deadline inherited from the caller; teardown still gives each step at least the
     *   cleanup grace, so an already-expired deadline cannot skip a termination or a secret removal.
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
    ): TerminalSnapshot = closeWithoutActionLock(reason, deadline, maxLines, maxBytes, ioDispatcher)

    /**
     * Finalizes the session as closed without touching the action lock.
     *
     * An action that already holds the action lock and has observed that the profile and the shell
     * have both ended uses this: waiting for its own lock would deadlock, and the teardown it needs
     * does not depend on the lock.
     *
     * @param deadline Deadline inherited from the caller; teardown still gives each step at least the
     *   cleanup grace, so an already-expired deadline cannot skip a termination or a secret removal.
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @param ioDispatcher Dispatcher used for the blocking termination path.
     * @return The final snapshot, with status `CLOSED`.
     */
    suspend fun closeAfterProfileExit(
        deadline: Long,
        maxLines: Int,
        maxBytes: Int,
        ioDispatcher: CoroutineDispatcher,
    ): TerminalSnapshot = closeWithoutActionLock(
        reason = TerminalCloseReason.PROFILE_EXITED,
        deadline = deadline,
        maxLines = maxLines,
        maxBytes = maxBytes,
        ioDispatcher = ioDispatcher,
    )

    /**
     * Tears down both targets and finalizes the tombstone without acquiring the action lock.
     *
     * @param reason Reason recorded on the tombstone.
     * @param deadline Deadline inherited from the caller; teardown still gives each step at least the
     *   cleanup grace, so an already-expired deadline cannot skip a termination or a secret removal.
     * @param maxLines Maximum lines returned with the final snapshot.
     * @param maxBytes Maximum bytes returned with the final snapshot.
     * @param ioDispatcher Dispatcher used for the blocking termination path.
     * @return The final snapshot, with status `CLOSED`.
     */
    private suspend fun closeWithoutActionLock(
        reason: TerminalCloseReason,
        deadline: Long,
        maxLines: Int,
        maxBytes: Int,
        ioDispatcher: CoroutineDispatcher,
    ): TerminalSnapshot {
        synchronized(stateLock) { closing = true }
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
    ): TerminalSnapshot {
        synchronized(stateLock) {
            if (lifecycle != TerminalLifecycle.CLOSED) {
                lifecycle = TerminalLifecycle.CLOSED
                closedReasonValue = reason
                closedAtNanosValue = clock()
            }
        }
        return readSnapshot(maxLines, maxBytes)
    }

    /**
     * Tears down the active target, then the shell, then anything a concurrent attach staged.
     *
     * The active target is torn down first so a profile that is still running cannot outlive the
     * session that owns it. A staged profile has no registered target yet, so it is reclaimed through
     * the handle the attach call published; without that, a close that wins the action-lock race would
     * orphan the profile process and its written secret.
     *
     * Every step is given at least the cleanup grace, so a spent caller budget - the preparation that
     * preceded the attach, or the sessions closed before this one - cannot turn the teardown into a
     * no-op that leaves a process running or a secret tree on disk.
     *
     * @param deadline Deadline inherited from the caller, possibly already expired.
     */
    private fun teardown(deadline: Long) {
        val (current, profile, staged) = synchronized(stateLock) {
            Triple(active, attached, pendingProfile)
        }
        if (current !== shell) current.teardown(withCleanupGrace(deadline))
        shell.teardown(withCleanupGrace(deadline))
        runCatching { profile?.material?.close(withCleanupGrace(deadline)) }
        runCatching { staged?.close(deadline) }
        synchronized(stateLock) {
            attached = null
            attachedTarget = null
            pendingProfile = null
        }
    }

    /**
     * @param target Target whose status is being read.
     * @param targetStatus Status the target derived from its own state.
     * @return Status the session reports for [target].
     */
    private fun statusOf(target: SessionTarget, targetStatus: TargetStatus): TerminalStatus = when {
        lifecycle == TerminalLifecycle.CLOSED -> TerminalStatus.CLOSED
        target !== shell -> TerminalStatus.ATTACHED
        else -> when (targetStatus) {
            TargetStatus.EXITED -> TerminalStatus.EXITED
            TargetStatus.QUIET -> TerminalStatus.QUIET
            TargetStatus.RUNNING -> TerminalStatus.RUNNING
        }
    }

    /** Records an action timestamp; the caller must hold [stateLock]. */
    private fun recordActivityLocked() {
        lastActionNanos = clock()
    }

    /**
     * Attachment facts a snapshot reports.
     *
     * @property attached Profile that is attached, or null while the shell is active.
     * @property transition Target change this call caused.
     * @property exitCode Exit code of the profile that stopped on this call.
     */
    private class AttachSnapshotState(
        val attached: CredentialProfile?,
        val transition: AttachTransition,
        val exitCode: Int?,
    )

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
        /** Poll interval while waiting for a concurrent close to finalize. */
        const val CLOSE_WAIT_MILLIS = 10L
    }
}
