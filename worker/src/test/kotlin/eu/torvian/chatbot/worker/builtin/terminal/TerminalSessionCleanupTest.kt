package eu.torvian.chatbot.worker.builtin.terminal

import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.worker.builtin.command.CommandTerminator
import eu.torvian.chatbot.worker.builtin.command.TrackedCommand
import eu.torvian.chatbot.worker.builtin.command.remainingNanos
import eu.torvian.chatbot.worker.builtin.terminal.secret.FileSecretMaterial
import eu.torvian.chatbot.worker.credential.CredentialProfile
import eu.torvian.chatbot.worker.credential.SecretValue
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Verifies that cleanup still works when the deadline it inherits is already spent.
 *
 * The collaborators here honour their deadline like the production ones do: the terminator delivers its
 * signal only while the relay has time to run, and the tree factory removes a tree only while the helper
 * has time to run. A process that also refuses direct kills stands in for a target running under the
 * credential account, which the worker itself may not signal.
 */
class TerminalSessionCleanupTest {

    /** Root holding the per-attach secret trees. */
    private val secretRoot: Path = createTempDirectory("terminal-cleanup-secrets")

    /** Logger used by the session and its targets. */
    private val logger: Logger = LogManager.getLogger(TerminalSessionCleanupTest::class.java)

    /** Removes the temporary tree root once a test finishes. */
    @AfterTest
    fun cleanUp() {
        secretRoot.toFile().deleteRecursively()
    }

    /**
     * Verifies that a teardown with an expired deadline still stops both processes and removes the secret.
     *
     * Shutdown closes up to the configured number of sessions from one shared budget, and an attach that
     * overran its preparation budget reaches its own cleanup the same way. Neither may degrade into a
     * silent no-op: the deadline each cleanup step receives is re-derived, so the helper is started with
     * time to signal the process and to remove the tree.
     */
    @Test
    fun `cleanup stops the processes and removes the secret when the deadline is already spent`(): Unit =
        runBlocking {
            val shellProcess = FakeTerminalProcess().apply { refuseDirectKill() }
            val profileProcess = FakeTerminalProcess().apply { refuseDirectKill() }
            val shellTerminator = DeadlineGatedTerminator(shellProcess)
            val profileTerminator = DeadlineGatedTerminator(profileProcess)
            val trees = DeadlineGatedTreeFactory(secretRoot)
            val session = TerminalSession(
                id = "aB3xY9",
                shell = target("shell", shellProcess, shellTerminator),
                idleTimeoutSeconds = 60,
                logger = logger,
            )
            val material = assertNotNull(
                FileSecretMaterial
                    .create(trees, "credentials.json", "SECRET_FILE", SecretValue("token".toCharArray()), freshDeadline(), logger)
                    .getOrNull()
            )
            assertTrue(session.attach(profile(), target("profile", profileProcess, profileTerminator), material))
            assertEquals(1, trees.remaining(), "the material created no secret tree to clean up")

            val snapshot = session.close(
                reason = TerminalCloseReason.SHUTDOWN,
                deadline = spentDeadline(),
                maxLines = 200,
                maxBytes = 4_096,
                ioDispatcher = Dispatchers.IO,
                actionLockWaitNanos = 0L,
            )

            assertEquals(TerminalStatus.CLOSED, snapshot.status)
            assertFalse(profileProcess.isAlive, "the profile process outlived a teardown with a spent deadline")
            assertFalse(shellProcess.isAlive, "the shell process outlived a teardown with a spent deadline")
            assertEquals(0, trees.remaining(), "the secret tree outlived a teardown with a spent deadline")
            val requestedTime = shellTerminator.remainingAtCall + profileTerminator.remainingAtCall
            assertTrue(
                requestedTime.all { it > 0L },
                "a termination was asked to run on an exhausted deadline: $requestedTime",
            )
        }

    /**
     * Verifies that reclaiming a staged profile grants each of its cleanup steps usable time.
     *
     * An attach hands its staged handle the preparation's deadline, which a slow preparation can spend
     * before the profile is registered, so the handle itself has to give the termination and the secret
     * removal a usable deadline rather than pass an expired one through.
     */
    @Test
    fun `reclaiming a staged profile stops it and removes its secret with a spent deadline`() {
        val process = FakeTerminalProcess().apply { refuseDirectKill() }
        val terminator = DeadlineGatedTerminator(process)
        val trees = DeadlineGatedTreeFactory(secretRoot)
        val material = assertNotNull(
            FileSecretMaterial
                .create(trees, "credentials.json", "SECRET_FILE", SecretValue("token".toCharArray()), freshDeadline(), logger)
                .getOrNull()
        )
        val staged = PendingProfile(
            profile = "vps",
            terminate = { deadline -> terminator.terminate(TrackedCommand(process), deadline) },
            material = material,
        )

        staged.close(spentDeadline())

        assertFalse(process.isAlive, "the staged profile outlived a reclaim with a spent deadline")
        assertEquals(0, trees.remaining(), "the secret tree outlived a reclaim with a spent deadline")
        assertTrue(
            terminator.remainingAtCall.all { it > 0L },
            "the termination was asked to run on an exhausted deadline: ${terminator.remainingAtCall}",
        )
    }

    /**
     * Verifies that detaching with an expired deadline still stops the profile and removes its secret.
     *
     * Detach shares the caller's deadline between the profile teardown and the secret removal, so a
     * process that had to be killed can consume the whole budget before the tree is removed.
     */
    @Test
    fun `detaching with a spent deadline still stops the profile and removes its secret`() {
        val shellProcess = FakeTerminalProcess()
        val profileProcess = FakeTerminalProcess().apply { refuseDirectKill() }
        val trees = DeadlineGatedTreeFactory(secretRoot)
        val session = TerminalSession(
            id = "aB3xY9",
            shell = target("shell", shellProcess, DeadlineGatedTerminator(shellProcess)),
            idleTimeoutSeconds = 60,
            logger = logger,
        )
        val material = assertNotNull(
            FileSecretMaterial
                .create(trees, "credentials.json", "SECRET_FILE", SecretValue("token".toCharArray()), freshDeadline(), logger)
                .getOrNull()
        )
        assertTrue(
            session.attach(
                profile(),
                target("profile", profileProcess, DeadlineGatedTerminator(profileProcess)),
                material,
            )
        )

        session.detach(DetachReason.REQUESTED, spentDeadline(), MAX_LINES, MAX_BYTES)

        assertFalse(profileProcess.isAlive, "the profile outlived a detach with a spent deadline")
        assertTrue(shellProcess.isAlive, "detach must leave the shell running")
        assertEquals(0, trees.remaining(), "the secret tree outlived a detach with a spent deadline")
    }

    /**
     * Builds one target for the session under test.
     *
     * @param name Target name used in log lines.
     * @param process Process the target owns.
     * @param terminator Strategy that ends [process].
     * @return A target with production defaults for buffering and quiescence.
     */
    private fun target(
        name: String,
        process: FakeTerminalProcess,
        terminator: CommandTerminator,
    ): SessionTarget = SessionTarget(
        name = name,
        process = process,
        terminator = terminator,
        buffer = OutputRingBuffer(4_096),
        sanitizer = TerminalOutputSanitizer(0.15),
        quietMillis = 20,
        logger = logger,
    )

    /**
     * @return A deadline that is still in the future, used for the preparation step.
     */
    private fun freshDeadline(): Long =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(5)

    /**
     * @return A deadline that passed one millisecond ago.
     */
    private fun spentDeadline(): Long =
        System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1)

    /**
     * Terminator that delivers a signal only while the deadline leaves it time to run.
     *
     * Mirrors the helper relay, which is started with whatever the deadline leaves and is killed before
     * it can signal once nothing is left.
     *
     * @property process Process the signal ends.
     */
    private class DeadlineGatedTerminator(private val process: FakeTerminalProcess) : CommandTerminator {
        /** Time the deadline left at every call, in nanoseconds. */
        val remainingAtCall = CopyOnWriteArrayList<Long>()

        /** Does nothing: this fake signals the process it was built for. */
        override fun track(command: TrackedCommand) = Unit

        override fun terminate(command: TrackedCommand, deadline: Long) {
            val remaining = remainingNanos(deadline)
            remainingAtCall += remaining
            if (remaining > 0L) process.finish(143)
        }
    }

    /**
     * @return A profile whose secret is delivered through the file channel.
     */
    private fun profile(): CredentialProfile = CredentialProfile(
        id = 1,
        workerId = 7,
        name = "vps",
        description = "Test profile",
        secretRef = "vps_password",
        command = "/usr/bin/env",
        args = listOf("id"),
        channel = CredentialChannel.FILE,
        envName = "GH_TOKEN",
        fileName = "credentials.json",
        askpassUsername = null,
        isEnabled = true,
        environmentVariables = emptyMap(),
        askpassVariables = emptyList(),
    )

    private companion object {
        /** Line cap handed to a detach, which returns the profile's unread tail. */
        const val MAX_LINES = 20

        /** Byte cap handed to a detach, which returns the profile's unread tail. */
        const val MAX_BYTES = 200
    }
}
