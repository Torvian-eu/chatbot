package eu.torvian.chatbot.worker.builtin.command

import java.io.Closeable
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.Logger

/**
 * Delivers one signal to a process group.
 *
 * A separate abstraction because relay delivery is the only part of the strategy that spawns a
 * process: keeping it behind an interface lets the TERM-then-KILL sequencing be verified without a
 * setuid helper, and keeps process spawning out of the sequencing logic.
 */
fun interface ProcessGroupSignaller {
    /**
     * Signals every process of [processGroupId].
     *
     * @param processGroupId Process-group id; the command's pid, because the helper makes the
     *   command its own group leader.
     * @param signal Signal to deliver.
     * @param waitNanos Upper bound on how long this invocation may take.
     * @return `true` when the signal was delivered or the group no longer exists, `false` when it
     *   could not be delivered; the implementation reports the latter at WARN.
     */
    fun signal(processGroupId: Long, signal: CommandSignal, waitNanos: Long): Boolean
}

/**
 * Signals a process group through the setuid helper.
 *
 * The relay is a short-lived helper invocation that drops to the isolated account before it
 * signals, so sender and target share an account and the kernel permits the signal. It is spawned
 * with an empty environment: while it lives, any same-account process can read its
 * `/proc/<pid>/environ`, and passing the worker's environment would leak exactly what isolation
 * protects.
 *
 * @property launcher Launcher producing the helper argv, which keeps the helper path single-sourced.
 * @property logger Logger receiving a WARN when a signal cannot be delivered.
 */
class HelperProcessGroupSignaller(
    private val launcher: CommandLauncher,
    private val logger: Logger,
) : ProcessGroupSignaller {
    override fun signal(processGroupId: Long, signal: CommandSignal, waitNanos: Long): Boolean {
        // The launcher turns this into [helperPath, "--signal", <signal>, <pgid>]; the helper it
        // names is the binary the probe already proved able to drop privileges.
        val argv = launcher.wrap(SIGNAL_MODE_ARGUMENT, listOf(signal.name, processGroupId.toString()))
        val relay = try {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .apply { environment().clear() }
                .start()
        } catch (e: Exception) {
            logger.warn(
                "Could not start the command-isolation helper to deliver {} to process group {}: {}",
                signal,
                processGroupId,
                e.message
            )
            return false
        }

        return try {
            // The relay reads nothing from stdin; EOF must be observable immediately.
            relay.outputStream.close()
            when {
                !relay.waitFor(waitNanos, TimeUnit.NANOSECONDS) -> {
                    logger.warn(
                        "Command-isolation helper did not exit within the cleanup budget while " +
                            "delivering {} to process group {}; the group may still be running",
                        signal,
                        processGroupId
                    )
                    // Almost always refused, because the relay has already dropped; attempted only
                    // so a relay that hung before its drop does not stay unaddressed silently.
                    relay.destroyForcibly()
                    false
                }

                // Exit 0 also covers "the group no longer exists", which is the normal outcome of
                // the second attempt and must not be reported as a failure.
                relay.exitValue() == 0 -> true

                else -> {
                    val detail = relay.inputStream.readBytes().decodeToString().trim()
                    logger.warn(
                        "Command-isolation helper could not deliver {} to process group {}: exit {}",
                        signal,
                        processGroupId,
                        relay.exitValue()
                    )
                    if (detail.isNotEmpty()) {
                        logger.warn("Command-isolation helper reported: {}", detail)
                    }
                    false
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Command-isolation helper failed while delivering {} to process group {}: {}",
                signal,
                processGroupId,
                e.message
            )
            false
        } finally {
            // A timeout and a cancellation each run one relay, so leaving the pipes to the garbage
            // collector makes the worker's descriptor count depend on GC timing.
            closeQuietly(relay.inputStream)
            closeQuietly(relay.outputStream)
        }
    }

    /**
     * Closes a relay stream while suppressing the errors a helper that already exited can cause.
     *
     * @param stream Stream to close.
     */
    private fun closeQuietly(stream: Closeable) {
        runCatching { stream.close() }
    }

    private companion object {
        /** argv[1] value that selects the helper's signal mode. */
        const val SIGNAL_MODE_ARGUMENT = "--signal"
    }
}

/**
 * Terminates a tree by signalling the command's process group through the helper.
 *
 * Used while a privilege drop is active. The helper runs the command as its own process-group
 * leader, so signalling the group also reaches descendants that were reparented and that a
 * `ProcessHandle.descendants()` scan can no longer see. A descendant that calls `setsid()` leaves
 * the group and stays unreachable, exactly as it is for a handle-based strategy.
 *
 * @property signaller Delivery primitive used for both signals.
 */
class HelperSignalRelay(private val signaller: ProcessGroupSignaller) : CommandTerminator {
    /**
     * Does nothing: signalling the group reaches every descendant without observing any of them.
     */
    override fun track(command: TrackedCommand) = Unit

    override fun terminate(command: TrackedCommand, deadline: Long) {
        val processGroupId = command.root.pid()
        // TERM first, so a well-behaved command can exit on its own. The KILL afterwards is
        // unconditional: an exited root says nothing about descendants that stayed in the group,
        // and an already empty group is reported as success by the relay.
        signaller.signal(processGroupId, CommandSignal.TERM, relayWaitNanos(deadline))
        waitForRootExit(command.root, minOf(deadline, deadlineAfterNanos(SIGNAL_SETTLE_NANOS)))
        signaller.signal(processGroupId, CommandSignal.KILL, relayWaitNanos(deadline))
        waitForRootExit(command.root, deadline)
    }

    /**
     * Bounds one relay invocation so two of them cannot exhaust the shared cleanup budget.
     *
     * @param deadline Monotonic deadline of the current cleanup pass.
     * @return Nanoseconds the relay may take, never more than what is left of [deadline].
     */
    private fun relayWaitNanos(deadline: Long): Long = minOf(remainingNanos(deadline), RELAY_WAIT_NANOS)

    /**
     * Reaps the direct child without ever exceeding the cleanup budget.
     *
     * The command is still the worker's own child, so the authoritative `Process.waitFor` reaps it;
     * a root that outlives the budget is left to the JVM's process reaper, exactly as in the other
     * strategy.
     *
     * @param process Direct child of this worker.
     * @param deadline Monotonic deadline of the current cleanup pass.
     */
    private fun waitForRootExit(process: Process, deadline: Long) {
        val remaining = remainingNanos(deadline)
        if (remaining > 0L) {
            runCatching { process.waitFor(remaining, TimeUnit.NANOSECONDS) }
        }
    }

    private companion object {
        /** Opportunity for a TERMed group to exit before it is killed. */
        const val SIGNAL_SETTLE_NANOS: Long = 100_000_000L

        /** Upper bound on one relay invocation. */
        const val RELAY_WAIT_NANOS: Long = 500_000_000L
    }
}
