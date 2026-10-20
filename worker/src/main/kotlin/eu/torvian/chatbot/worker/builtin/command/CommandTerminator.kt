package eu.torvian.chatbot.worker.builtin.command

import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * Termination signal a terminator may deliver to a process group.
 */
enum class CommandSignal { TERM, KILL }

/**
 * Per-command state a [CommandTerminator] accumulates while one command runs.
 *
 * One instance exists per started command, so a terminator shared by the whole worker holds no
 * cross-command state. The tool creates it, refreshes it while the command runs, and hands it back
 * for termination; only the terminator that produced it interprets it.
 *
 * @property root Direct child started by the tool, used to reap the command and to address its
 *   process group.
 */
class TrackedCommand internal constructor(internal val root: Process) {
    /**
     * Descendants observed while the root was alive, keyed by pid.
     *
     * Only a strategy that signals individual handles reads or populates this; a group-signalling
     * strategy reaches descendants without observing them.
     */
    internal val descendants: MutableMap<Long, ProcessHandle> = LinkedHashMap()
}

/**
 * Terminates the process tree started by a built-in tool.
 *
 * A command can run under an OS account the worker may not signal, so the strategy belongs to the
 * resolved isolation state rather than to the tool: the resolution is the only component that knows
 * whether a signal can be delivered directly or has to be relayed.
 */
interface CommandTerminator {
    /**
     * Records [command]'s currently observable descendants.
     *
     * Called repeatedly while the command runs, because a descendant can be reparented as soon as
     * its parent exits and a single scan would then miss it.
     *
     * @param command Tracking state of the running command.
     */
    fun track(command: TrackedCommand)

    /**
     * Terminates the tree of [command] and reaps its root.
     *
     * Blocking work: callers invoke it inside a non-cancellable cleanup scope on a dispatcher that
     * tolerates blocking. Implementations must return before [deadline], because that budget is
     * shared with reader teardown, and must not throw for a process that is already gone.
     *
     * @param command Tracking state of the command to terminate.
     * @param deadline Monotonic deadline in [System.nanoTime] units.
     */
    fun terminate(command: TrackedCommand, deadline: Long)
}

/**
 * Terminates a tree by signalling the Java process handles observed for it.
 *
 * Used when no privilege drop is active: the worker then owns the children and may signal them
 * directly. Java handles remain best-effort rather than a security boundary, because detached or
 * reparented descendants may no longer be visible.
 */
class ProcessHandleCommandTerminator : CommandTerminator {
    override fun track(command: TrackedCommand) {
        val root = command.root.toHandle()
        runCatching {
            root.descendants().forEach { handle -> command.descendants.putIfAbsent(handle.pid(), handle) }
        }
    }

    override fun terminate(command: TrackedCommand, deadline: Long) {
        // Capture before signalling the root. A child can be reparented as soon as the root exits,
        // so a post-exit descendants() call alone cannot find a pipe-holding child.
        track(command)
        var descendants = command.descendants.values.toList()
        descendants.forEach(::destroyForcibly)

        // Let ordinary parents observe and reap killed children without delaying root termination.
        val parentReapDeadline = minOf(deadline, deadlineAfterNanos(DESCENDANT_PRE_ROOT_WAIT_NANOS))
        waitForDescendants(descendants, parentReapDeadline)
        runCatching { command.root.destroyForcibly() }
        waitForProcessTree(command.root, descendants, deadline)

        // This rescan catches children created during cleanup while the root was still visible.
        track(command)
        descendants = command.descendants.values.toList()
        descendants.forEach(::destroyForcibly)
        waitForProcessTree(command.root, descendants, deadline)
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

    private companion object {
        /** Short opportunity for a parent to reap signalled descendants before root termination. */
        const val DESCENDANT_PRE_ROOT_WAIT_NANOS: Long = 100_000_000L
    }
}

/**
 * Computes a monotonic deadline without letting nanosecond addition overflow.
 *
 * Shared by the tool and both terminator strategies, which measure time against the same cleanup
 * budget; a second copy of this arithmetic would be a second place to get the saturation wrong.
 *
 * @param durationNanos Non-negative duration to add to the current monotonic time.
 * @return Saturated monotonic deadline in nanoseconds.
 */
internal fun deadlineAfterNanos(durationNanos: Long): Long {
    val now = System.nanoTime()
    return if (durationNanos > 0L && now > Long.MAX_VALUE - durationNanos) {
        Long.MAX_VALUE
    } else {
        now + durationNanos
    }
}

/**
 * Returns the non-negative time remaining before a monotonic deadline.
 *
 * @param deadline Deadline produced by [deadlineAfterNanos].
 * @return Remaining nanoseconds, or zero after the deadline.
 */
internal fun remainingNanos(deadline: Long): Long =
    (deadline - System.nanoTime()).coerceAtLeast(0L)
