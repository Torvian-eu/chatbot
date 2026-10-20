package eu.torvian.chatbot.worker.builtin.command

import eu.torvian.chatbot.worker.builtin.impl.RunCommandTestSupport
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the TERM-then-KILL sequencing of the helper-backed termination strategy.
 *
 * The group signal delivery is injected, so the sequencing, the addressing and the wait bounds are
 * asserted without a setuid helper, a container or any process running under another account.
 */
class HelperSignalRelayTest {

    /**
     * Records the group signals the relay asks for and answers with a fixed outcome.
     *
     * @property outcome Value returned for every request.
     */
    private class RecordingSignaller(private val outcome: Boolean = true) : ProcessGroupSignaller {
        /** One entry per request, in call order. */
        val requests = mutableListOf<Request>()

        /**
         * Recorded signal request.
         *
         * @property processGroupId Group addressed by the request.
         * @property signal Signal requested.
         * @property waitNanos Upper bound passed with the request.
         */
        data class Request(val processGroupId: Long, val signal: CommandSignal, val waitNanos: Long)

        override fun signal(processGroupId: Long, signal: CommandSignal, waitNanos: Long): Boolean {
            requests += Request(processGroupId, signal, waitNanos)
            return outcome
        }

        /** Signals requested so far, in call order. */
        val signals: List<CommandSignal> get() = requests.map { it.signal }
    }

    /** Verifies that a termination attempt asks for TERM before KILL on the child's process group. */
    @Test
    fun `terminate delivers TERM then KILL to the child process group`() {
        val process = startLongRunningProcess()
        try {
            val signaller = RecordingSignaller()
            HelperSignalRelay(signaller).terminate(TrackedCommand(process), deadlineAfter(700))

            assertEquals(listOf(CommandSignal.TERM, CommandSignal.KILL), signaller.signals)
            assertTrue(
                signaller.requests.all { it.processGroupId == process.pid() },
                "Relay addressed a different process group: ${signaller.requests}"
            )
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /** Verifies that KILL is requested even when the root is already gone, because neither is its group. */
    @Test
    fun `terminate delivers KILL when the root has already exited`() {
        val process = startLongRunningProcess()
        val command = TrackedCommand(process)
        process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Test process did not exit")

        val signaller = RecordingSignaller()
        HelperSignalRelay(signaller).terminate(command, deadlineAfter(700))

        assertEquals(listOf(CommandSignal.TERM, CommandSignal.KILL), signaller.signals)
    }

    /** Verifies that each relay invocation is bounded by both the remaining budget and the relay bound. */
    @Test
    fun `relay wait bounds follow the remaining budget`() {
        val process = startLongRunningProcess()
        try {
            val capped = RecordingSignaller()
            HelperSignalRelay(capped).terminate(TrackedCommand(process), deadlineAfter(1_000))
            assertTrue(
                capped.requests.all { it.waitNanos == RELAY_WAIT_NANOS },
                "Relay budget was not capped at its own bound: ${capped.requests}"
            )

            val tight = RecordingSignaller()
            HelperSignalRelay(tight).terminate(TrackedCommand(process), deadlineAfter(200))
            assertTrue(
                tight.requests.all { it.waitNanos in 1..200_000_000L },
                "Relay budget exceeded the remaining cleanup budget: ${tight.requests}"
            )
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /** Verifies that termination stays inside its budget when nothing is delivered and nothing exits. */
    @Test
    fun `terminate returns within the deadline when the root never exits`() {
        val process = startLongRunningProcess()
        try {
            val signaller = RecordingSignaller(outcome = false)
            val startedAt = System.nanoTime()

            HelperSignalRelay(signaller).terminate(TrackedCommand(process), deadlineAfter(300))
            val elapsedNanos = System.nanoTime() - startedAt

            assertTrue(
                elapsedNanos < 2_000_000_000L,
                "Blocking termination exceeded the cleanup grace: $elapsedNanos ns"
            )
            // The signaller delivers nothing, so only the budget can end the wait.
            assertTrue(process.isAlive, "The test process exited and no longer covers the wait bound")
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /** Verifies that tracking is a no-op, because a group signal never needs observed handles. */
    @Test
    fun `track observes nothing and leaves the process alone`() {
        val process = startLongRunningProcess()
        try {
            val command = TrackedCommand(process)
            HelperSignalRelay(RecordingSignaller()).track(command)

            assertTrue(command.descendants.isEmpty(), "Group signalling must not observe descendants")
            assertTrue(process.isAlive, "Tracking must not touch the command")
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /**
     * Starts a process that outlives a test without needing a shell script.
     *
     * @return A running process that the caller must terminate.
     */
    private fun startLongRunningProcess(): Process {
        val (command, args) = RunCommandTestSupport.hangCommand()
        return ProcessBuilder(listOf(command) + args).start()
    }

    /**
     * Builds a monotonic deadline [millis] from now.
     *
     * @param millis Milliseconds of budget.
     * @return Deadline in [System.nanoTime] units.
     */
    private fun deadlineAfter(millis: Long): Long = System.nanoTime() + millis * 1_000_000L

    private companion object {
        /** Upper bound the relay itself applies to one invocation. */
        const val RELAY_WAIT_NANOS: Long = 500_000_000L
    }
}
