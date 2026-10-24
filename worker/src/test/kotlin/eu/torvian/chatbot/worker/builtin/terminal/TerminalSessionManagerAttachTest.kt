package eu.torvian.chatbot.worker.builtin.terminal

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.worker.builtin.command.ChildEnvironmentProvider
import eu.torvian.chatbot.worker.builtin.command.CommandIsolationResolution
import eu.torvian.chatbot.worker.builtin.command.SecretCommandAccount
import eu.torvian.chatbot.worker.builtin.terminal.secret.SecretMaterialFactory
import eu.torvian.chatbot.worker.config.TerminalSessionsConfig
import eu.torvian.chatbot.worker.credential.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Attach/detach behaviour of [TerminalSessionManager].
 *
 * A fake process stands in for the PTY and a temporary directory stands in for the helper-owned secret
 * tree, so the state machine, the argv/environment the profile receives and every cleanup path are
 * exercised without a setuid helper or a Linux account.
 */
class TerminalSessionManagerAttachTest {

    /** Working directory handed to shell sessions. */
    private val workspace: Path = createTempDirectory("terminal-attach-workspace")

    /** Root of the temporary secret trees. */
    private val secretRoot: Path = createTempDirectory("terminal-attach-secrets")

    /** Profile working directory, which must never be the shared workspace. */
    private val profileWork: Path = createTempDirectory("terminal-attach-work")

    /** Logger used by the manager; the tests assert on state rather than log lines. */
    private val logger: Logger = LogManager.getLogger(TerminalSessionManagerAttachTest::class.java)

    /** Managers created by a test, closed so no reaper scope or reader thread leaks. */
    private val managers = mutableListOf<TerminalSessionManager>()

    /** Temporary directories holding recording helper stubs. */
    private val helperDirectories = mutableListOf<Path>()

    /** Closes every manager and removes the temporary directories. */
    @AfterTest
    fun cleanUp() {
        managers.forEach { runCatching { it.close() } }
        workspace.toFile().deleteRecursively()
        secretRoot.toFile().deleteRecursively()
        profileWork.toFile().deleteRecursively()
        helperDirectories.forEach { it.toFile().deleteRecursively() }
    }

    /** Verifies that attach runs the profile through the credential launcher and directory. */
    @Test
    fun `attach uses the credential launcher, profile argv and profile working directory`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val factory = QueuedProcessFactory(shell, profile)
        val fixture = Fixture(factory)
        val sessionId = fixture.startSession()

        val snapshot = assertNotNull(
            fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull()
        )

        val spec = assertNotNull(factory.lastSpec)
        assertEquals(listOf(VAULT_HELPER, "/usr/bin/env", "id"), spec.argv)
        assertEquals(profileWork.toString(), spec.workingDirectory.toString())
        // The credential account's own identity comes from the vault provider, not the worker's.
        assertEquals("/home/vault", spec.environment["HOME"])
        assertEquals("vault", spec.environment["USER"])
        assertEquals("token", spec.environment["GH_TOKEN"])
        assertEquals("xterm-256color", spec.environment["TERM"])

        assertEquals(TerminalStatus.ATTACHED, snapshot.status)
        assertEquals(AttachTransition.ATTACHED, snapshot.transition)
        assertTrue(snapshot.attached)
        assertEquals("vps", snapshot.attachProfile)
        assertEquals(CredentialChannel.ENV, snapshot.attachChannel)
        assertNull(snapshot.attachExitCode)
    }

    /** Verifies that the secret reference and value never appear in a successful result. */
    @Test
    fun `attach result carries no secret material`(): Unit = runBlocking {
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess(), FakeTerminalProcess()))
        val sessionId = fixture.startSession()

        val snapshot = assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        assertFalse(snapshot.output.contains("token"))
        assertFalse(snapshot.attachProfile.orEmpty().contains(SECRET_REF))
    }

    /** Verifies that a session with pending shell output cannot attach. */
    @Test
    fun `attach requires a quiet shell`(): Unit = runBlocking {
        val factory = QueuedProcessFactory(FakeTerminalProcess())
        // A quiescence window the test can never reach makes the shell permanently non-quiet, so the
        // rejection does not depend on how fast the reader drains the prompt.
        val fixture = Fixture(factory, config = testTerminalConfig(quietMillis = 5_000, defaultWaitMillis = 100))
        val sessionId = fixture.startSession()

        val error = assertIs<TerminalSessionError.NotQuiet>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )

        assertEquals(sessionId, error.sessionId)
        assertEquals(1, factory.startedCount, "A rejected attach must not spawn a process")
    }

    /** Verifies that a second attach on an attached session is rejected without spawning. */
    @Test
    fun `attach rejects a session that is already attached`(): Unit = runBlocking {
        val factory = QueuedProcessFactory(FakeTerminalProcess(), FakeTerminalProcess())
        val fixture = Fixture(factory)
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        val error = assertIs<TerminalSessionError.AlreadyAttached>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )

        assertEquals("vps", error.profile)
        assertEquals(2, factory.startedCount)
    }

    /** Verifies that an unknown profile lists the names that are assigned instead. */
    @Test
    fun `attach reports an unknown profile with the assigned names`(): Unit = runBlocking {
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess()))
        val sessionId = fixture.startSession()

        val error = assertIs<TerminalSessionError.UnknownProfile>(
            fixture.manager.attach(sessionId, "missing", 0, 20, 200).swap().getOrNull()
        )

        assertEquals("missing", error.profile)
        assertEquals(listOf("vps"), error.known)
    }

    /** Verifies that a worker without a credential account refuses to attach at all. */
    @Test
    fun `attach without a credential account is denied`(): Unit = runBlocking {
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess()), attachDependencies = false)
        val sessionId = fixture.startSession()

        val error = assertIs<TerminalSessionError.CredentialSupportUnavailable>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )

        assertTrue(error.detail.contains("commandIsolation"), "Unexpected detail: ${error.detail}")
    }

    /** Verifies that a decryption failure is reported as an unavailable credential, not as input. */
    @Test
    fun `attach reports a decryption failure as credential unavailable`(): Unit = runBlocking {
        val fixture = Fixture(
            factory = QueuedProcessFactory(FakeTerminalProcess()),
            decryptor = object : CredentialSecretDecryptor {
                override fun decrypt(secret: EncryptedWorkerSecret): Either<SecretResolutionError, SecretValue> =
                    SecretResolutionError.KeyMismatch(secret.reference, "old", "new").left()
            },
        )
        val sessionId = fixture.startSession()

        val error = assertIs<TerminalSessionError.CredentialUnavailable>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )

        assertEquals("vps", error.profile)
        assertTrue(error.detail.contains(SECRET_REF), "Unexpected detail: ${error.detail}")
        // A rotation is named explicitly instead of surfacing as a generic failure.
        assertTrue(error.detail.contains("old") && error.detail.contains("new"))
    }

    /** Verifies that a failed spawn releases the written secret. */
    @Test
    fun `attach cleans up the secret tree when the spawn fails`(): Unit = runBlocking {
        val fixture = Fixture(
            factory = QueuedProcessFactory(FakeTerminalProcess()).apply { failOnStart = 2 },
            channel = CredentialChannel.FILE,
        )
        val sessionId = fixture.startSession()

        assertIs<TerminalSessionError.SpawnFailed>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )

        assertEquals(0, fixture.remainingSecretTrees(), "The secret tree outlived a failed spawn")
    }

    /** Verifies that a channel that cannot deliver the profile fails before anything is spawned. */
    @Test
    fun `attach reports a channel that cannot deliver`(): Unit = runBlocking {
        val factory = QueuedProcessFactory(FakeTerminalProcess())
        val fixture = Fixture(factory, channel = CredentialChannel.ASKPASS)
        val sessionId = fixture.startSession()

        val error = assertIs<TerminalSessionError.CredentialUnavailable>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )

        assertTrue(error.detail.contains("askpass"), "Unexpected detail: ${error.detail}")
        assertEquals(1, factory.startedCount, "A rejected channel must not spawn a process")
        assertEquals(0, fixture.remainingSecretTrees(), "A rejected channel must leave no secret behind")
    }

    /** Verifies that detach terminates the profile and returns the shell as the active target. */
    @Test
    fun `detach terminates the profile and returns the shell`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(shell, profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        val snapshot = assertNotNull(fixture.manager.detach(sessionId, 40, 20, 200).getOrNull())

        assertEquals(AttachTransition.DETACHED, snapshot.transition)
        assertFalse(snapshot.attached)
        assertNull(snapshot.attachProfile)
        assertFalse(profile.isAlive, "The profile process survived detach")

        // Input now reaches the shell again.
        assertNotNull(fixture.manager.send(sessionId, "ls\n", 40, 20, 200).getOrNull())
        assertEquals("ls\n", shell.writtenText())
    }

    /** Verifies that detach on a session without a profile is rejected. */
    @Test
    fun `detach requires an attached profile`(): Unit = runBlocking {
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess()))
        val sessionId = fixture.startSession()

        assertIs<TerminalSessionError.NotAttached>(
            fixture.manager.detach(sessionId, 40, 20, 200).swap().getOrNull()
        )
    }

    /** Verifies that a profile exit returns the session to its shell and reports the transition. */
    @Test
    fun `profile exit auto-detaches and reports the exit code`(): Unit = runBlocking {        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(shell, profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        profile.writeOutput("done\n")
        profile.finish(7)

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 120, 20, 200).getOrNull())

        assertEquals(AttachTransition.PROFILE_EXITED, snapshot.transition)
        assertEquals(7, snapshot.attachExitCode)
        assertFalse(snapshot.attached)
        assertTrue(
            snapshot.status == TerminalStatus.RUNNING || snapshot.status == TerminalStatus.QUIET,
            "Unexpected status ${snapshot.status}",
        )
    }

    /**
     * Verifies that the output a profile printed before it exited reaches the model.
     *
     * The exit is what a failing command reports after printing its diagnostic, and the profile target is
     * dropped when the shell becomes active again, so a call that reconciles the exit without reading the
     * target first answers with an exit code and nothing else.
     */
    @Test
    fun `a profile that prints and exits reports its output on the poll that observes it`(): Unit = runBlocking {
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess(), profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        profile.writeOutput("fatal: could not read token\n")
        profile.finish(255)

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 2_000, 20, 200).getOrNull())

        assertEquals(AttachTransition.PROFILE_EXITED, snapshot.transition)
        assertEquals(255, snapshot.attachExitCode)
        assertTrue(snapshot.output.contains("fatal: could not read token"), snapshot.output)
    }

    /**
     * Verifies that `attach` itself reports the output of a profile that fails immediately.
     *
     * This is the case the attach feature exists for: `ssh` with a bad key or `git` with a bad token
     * prints once and exits before the settle loop ever sees it running.
     */
    @Test
    fun `attach reports the output of a profile that fails before the session settles`(): Unit = runBlocking {
        val profile = FakeTerminalProcess()
        val spawn = GatedProcessFactory(QueuedProcessFactory(FakeTerminalProcess(), profile))
        val fixture = Fixture(spawn)
        val sessionId = fixture.startSession()

        spawn.arm()
        val attaching = async(Dispatchers.Default) { fixture.manager.attach(sessionId, "vps", 5_000, 20, 200) }
        assertTrue(spawn.awaitSpawn(5_000), "the attach never reached the spawn")
        // The profile prints and exits while the attach still holds the session lock.
        profile.writeOutput("Permission denied (publickey).\n")
        profile.finish(255)
        spawn.release()

        val snapshot = assertNotNull(attaching.await().getOrNull())

        assertEquals(AttachTransition.PROFILE_EXITED, snapshot.transition)
        assertEquals(255, snapshot.attachExitCode)
        assertTrue(snapshot.output.contains("Permission denied (publickey)."), snapshot.output)
    }

    /** Verifies that an explicit detach returns what the profile printed after the previous read. */
    @Test
    fun `detach returns the output the profile printed`(): Unit = runBlocking {
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess(), profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        profile.writeOutput("line one\nline two\nline three\n")

        val snapshot = assertNotNull(fixture.manager.detach(sessionId, 2_000, 20, 200).getOrNull())

        assertEquals(AttachTransition.DETACHED, snapshot.transition)
        assertTrue(snapshot.output.contains("line three"), snapshot.output)
    }

    /** Verifies that the tail a detach carries is bounded by the caller's per-call caps. */
    @Test
    fun `detach bounds the profile output it returns`(): Unit = runBlocking {
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess(), profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        profile.writeOutput("line one\nline two\nline three\n")

        val snapshot = assertNotNull(fixture.manager.detach(sessionId, 2_000, 1, 200).getOrNull())

        assertTrue(snapshot.output.contains("line one"), snapshot.output)
        assertFalse(snapshot.output.contains("line three"), "the line cap was not applied: ${snapshot.output}")
    }

    /**
     * Verifies that shell output produced while a profile is attached waits for the detach.
     *
     * The two targets keep separate buffers and cursors, so the shell's output must neither be lost nor
     * interleaved into a read of the profile.
     */
    @Test
    fun `shell output is held until the profile detaches`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(shell, FakeTerminalProcess()))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        shell.writeOutput("shell-only output\n")

        val whileAttached = assertNotNull(fixture.manager.poll(sessionId, 200, 20, 200).getOrNull())
        assertEquals(TerminalStatus.ATTACHED, whileAttached.status)
        assertFalse(whileAttached.output.contains("shell-only"), whileAttached.output)

        val detached = assertNotNull(fixture.manager.detach(sessionId, 2_000, 20, 200).getOrNull())

        assertEquals(AttachTransition.DETACHED, detached.transition)
        assertTrue(detached.output.contains("shell-only output"), detached.output)
    }

    /**
     * Verifies that a deleted profile leaves the running target alone and fails the next attach.
     *
     * An attached target owns its profile and its material, so an operator change must apply only to the
     * next attach rather than to a profile a command is already running under.
     */
    @Test
    fun `deleting a profile affects only the next attach`(): Unit = runBlocking {
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess(), profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        fixture.store.removeProfile(PROFILE_ID)

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 200, 20, 200).getOrNull())
        assertEquals(TerminalStatus.ATTACHED, snapshot.status)
        assertEquals("vps", snapshot.attachProfile)
        assertTrue(profile.isAlive, "a deleted profile stopped a running target")

        assertNotNull(fixture.manager.detach(sessionId, 40, 20, 200).getOrNull())
        assertIs<TerminalSessionError.UnknownProfile>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )
    }

    /** Verifies that disabling a profile leaves the running target alone and fails the next attach. */
    @Test
    fun `disabling a profile affects only the next attach`(): Unit = runBlocking {
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(FakeTerminalProcess(), profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        fixture.store.upsertProfile(profile(channel = CredentialChannel.ENV).copy(isEnabled = false))

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 200, 20, 200).getOrNull())
        assertEquals(TerminalStatus.ATTACHED, snapshot.status)
        assertTrue(profile.isAlive, "a disabled profile stopped a running target")

        assertNotNull(fixture.manager.detach(sessionId, 40, 20, 200).getOrNull())
        val error = assertIs<TerminalSessionError.CredentialUnavailable>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )
        assertTrue(error.detail.contains("disabled"), "Unexpected detail: ${error.detail}")
    }

    /**
     * Verifies that replacing or deleting a secret affects only the next attach, never a live target.
     *
     * The running profile already had the value delivered through its channel; re-reading the cache is
     * what would turn an operator change into an interrupted command.
     */
    @Test
    fun `replacing or deleting a secret affects only the next attach`(): Unit = runBlocking {
        val decryptor = RecordingDecryptor()
        val fixture = Fixture(
            QueuedProcessFactory(FakeTerminalProcess(), FakeTerminalProcess(), FakeTerminalProcess()),
            decryptor = decryptor,
        )
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())
        assertEquals(listOf(CACHED_SECRET_ID), decryptor.decrypted.map { it.id })

        fixture.store.upsertSecret(secret(id = REPLACED_SECRET_ID))

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 200, 20, 200).getOrNull())
        assertEquals(TerminalStatus.ATTACHED, snapshot.status)
        assertEquals(
            listOf(CACHED_SECRET_ID),
            decryptor.decrypted.map { it.id },
            "a replaced secret was decrypted for a target that is already running",
        )

        assertNotNull(fixture.manager.detach(sessionId, 40, 20, 200).getOrNull())
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())
        // The next attach resolves the replaced row, which is the only effect the change may have.
        assertEquals(REPLACED_SECRET_ID, decryptor.decrypted.last().id)

        fixture.store.removeSecret(REPLACED_SECRET_ID)
        assertNotNull(fixture.manager.detach(sessionId, 40, 20, 200).getOrNull())
        val error = assertIs<TerminalSessionError.CredentialUnavailable>(
            fixture.manager.attach(sessionId, "vps", 0, 20, 200).swap().getOrNull()
        )
        assertTrue(error.detail.contains("not assigned"), "Unexpected detail: ${error.detail}")
    }

    /** Verifies that a shell that exits while attached leaves the profile running. */
    @Test
    fun `shell exit while attached keeps the profile attached`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(shell, profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        shell.finish(0)

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 40, 20, 200).getOrNull())

        assertEquals(TerminalStatus.ATTACHED, snapshot.status)
        assertTrue(profile.isAlive, "The profile must keep running after the shell exited")
    }

    /** Verifies that the session closes once both the shell and the profile are gone. */
    @Test
    fun `session closes when the profile exits after the shell`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(shell, profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        shell.finish(0)
        // Which exit the session sees first decides the outcome, so wait for the state the session
        // derives instead of assuming a fixed delay.
        assertTrue(awaitShellExit(fixture.manager, sessionId), "the session never observed the shell exit")
        profile.finish(3)

        val snapshot = assertNotNull(fixture.manager.poll(sessionId, 120, 20, 200).getOrNull())

        assertEquals(TerminalStatus.CLOSED, snapshot.status)
        // The tombstone records why it ended, which a later write reports to the model.
        val closed = assertIs<TerminalSessionError.SessionClosed>(
            fixture.manager.send(sessionId, "ls", 0, 20, 200).swap().getOrNull()
        )
        assertEquals(TerminalCloseReason.PROFILE_EXITED, closed.reason)
    }

    /**
     * Verifies that cancelling an attach after the profile was spawned reclaims it.
     *
     * The process and the written secret exist before the profile becomes the active target, so the
     * only owner at that moment is the handle the attach published to the session. A cancellation that
     * skips that handle leaves a credential process running with the secret in its environment.
     */
    @Test
    fun `cancelling an attach after the spawn reclaims the profile and its secret`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val spawn = GatedProcessFactory(QueuedProcessFactory(shell, profile))
        val fixture = Fixture(spawn, channel = CredentialChannel.FILE)
        val sessionId = fixture.startSession()

        spawn.arm()
        val attach = async(Dispatchers.Default) { fixture.manager.attach(sessionId, "vps", 10_000, 20, 200) }
        assertTrue(spawn.awaitSpawn(5_000), "the attach never reached the spawn")
        attach.cancel()
        spawn.release()
        attach.join()

        assertTrue(attach.isCancelled, "the attach completed instead of being cancelled")
        assertFalse(profile.isAlive, "the staged profile process outlived the cancelled attach")
        assertTrue(shell.isAlive, "the shell must not be affected by the cancelled attach")
        assertEquals(0, fixture.remainingSecretTrees(), "the written secret outlived the cancelled attach")
    }

    /**
     * Verifies that reclaiming a cancelled attach signals through the credential account's helper.
     *
     * A profile target runs as the credential account, and only that account's helper can signal it;
     * the helper ordinary commands use drops to a different account and its signal is refused.
     */
    @Test
    fun `reclaim of a cancelled attach signals through the credential helper`(): Unit = runBlocking {
        val helpers = recordingHelpers()
        assumeTrue(helpers != null, "this host cannot run a recording helper stub")
        checkNotNull(helpers)

        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val spawn = GatedProcessFactory(QueuedProcessFactory(shell, profile))
        val fixture = Fixture(
            spawn,
            channel = CredentialChannel.FILE,
            agentHelper = helpers.agentHelper,
            vaultHelper = helpers.vaultHelper,
        )
        val sessionId = fixture.startSession()

        spawn.arm()
        val attach = async(Dispatchers.Default) { fixture.manager.attach(sessionId, "vps", 10_000, 20, 200) }
        assertTrue(spawn.awaitSpawn(5_000), "the attach never reached the spawn")
        attach.cancel()
        spawn.release()
        attach.join()

        val calls = helpers.recordedCalls()
        assertTrue(calls.isNotEmpty(), "no helper was invoked to reclaim the profile")
        assertTrue(
            calls.all { it.startsWith("vault ") },
            "the reclaim signalled through the wrong helper: $calls",
        )
        assertTrue(calls.first().contains("--signal"), "the helper was not asked to signal: $calls")
        assertFalse(profile.isAlive, "the staged profile process outlived the cancelled attach")
    }

    /**
     * Verifies that a close that loses the action-lock race still reclaims an in-flight attach.
     *
     * The attach holds the session lock while it spawns, so a close can only tear the session down
     * without the lock; it must still reach the secret the attach already wrote.
     */
    @Test
    fun `close while an attach holds the action lock reclaims the staged profile`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val spawn = GatedProcessFactory(QueuedProcessFactory(shell, profile))
        val fixture = Fixture(
            spawn,
            channel = CredentialChannel.FILE,
            shutdownGraceMillis = 300,
        )
        val sessionId = fixture.startSession()

        spawn.arm()
        val attach = async(Dispatchers.Default) { fixture.manager.attach(sessionId, "vps", 10_000, 20, 200) }
        assertTrue(spawn.awaitSpawn(5_000), "the attach never reached the spawn")

        // Shutdown uses a bounded action-lock wait, so this is the busy-close path, not a queued close.
        fixture.manager.close()

        assertEquals(0, fixture.remainingSecretTrees(), "the staged secret survived the busy close")
        assertFalse(shell.isAlive, "the shell process survived the busy close")

        spawn.release()
        val outcome = attach.await()
        assertIs<TerminalSessionError.SessionClosed>(outcome.swap().getOrNull())
        assertFalse(profile.isAlive, "the profile process outlived the closed session")
        assertEquals(0, fixture.remainingSecretTrees(), "the secret tree was recreated after the close")
    }

    /**
     * Verifies that shutdown stops every process and removes every secret even with a spent budget.
     *
     * Shutdown closes every live session from one shared budget, so by construction the sessions closed
     * last reach their cleanup with nothing left. A zero budget forces that state for all of them at
     * once: the secret removal must still run, because a tree left behind outlives the process that was
     * allowed to read it.
     */
    @Test
    fun `shutdown stops every process and removes every secret with a spent budget`(): Unit = runBlocking {
        val trees = DeadlineGatedTreeFactory(secretRoot)
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val secondShell = FakeTerminalProcess()
        val secondProfile = FakeTerminalProcess()
        val fixture = Fixture(
            QueuedProcessFactory(shell, profile, secondShell, secondProfile),
            channel = CredentialChannel.FILE,
            shutdownGraceMillis = 0L,
            secretTreeFactory = trees,
            // Two live sessions need two distinct ids, so this fixture counts instead of repeating one.
            idGenerator = counterIds(),
        )
        repeat(2) {
            val sessionId = fixture.startSession()
            assertNotNull(fixture.manager.attach(sessionId, "vps", 200, 20, 200).getOrNull())
        }
        assertEquals(2, fixture.remainingSecretTrees(), "the attaches created no secret trees")

        fixture.manager.close()

        assertEquals(
            0,
            fixture.remainingSecretTrees(),
            "a secret tree survived a shutdown whose shared grace was spent",
        )
        listOf(shell, profile, secondShell, secondProfile).forEach { process ->
            assertFalse(process.isAlive, "a process survived the shutdown")
        }
    }

    /** Verifies that closing an attached session terminates both processes. */
    @Test
    fun `close terminates the profile and the shell`(): Unit = runBlocking {
        val shell = FakeTerminalProcess()
        val profile = FakeTerminalProcess()
        val fixture = Fixture(QueuedProcessFactory(shell, profile))
        val sessionId = fixture.startSession()
        assertNotNull(fixture.manager.attach(sessionId, "vps", 40, 20, 200).getOrNull())

        val snapshot = assertNotNull(fixture.manager.close(sessionId, 20, 200).getOrNull())

        assertEquals(TerminalStatus.CLOSED, snapshot.status)
        assertFalse(profile.isAlive, "The profile process survived close")
        assertFalse(shell.isAlive, "The shell process survived close")
        assertEquals(0, fixture.remainingSecretTrees(), "The secret tree survived close")
    }

    /**
     * Credential collaborators and manager under test.
     *
     * @property factory Process factory recording the last spawn spec.
     * @property decryptor Decryptor seam; defaults to a successful one.
     * @property channel Channel the cached profile uses.
     * @property agentHelper Helper path the command account is built from.
     * @property vaultHelper Helper path the credential account is built from.
     * @property shutdownGraceMillis Shared shutdown budget, used to reach the busy-close path.
     * @property secretTreeFactory Temporary-directory tree factory standing in for the helper.
     */
    private inner class Fixture(
        val factory: TerminalProcessFactory,
        val decryptor: CredentialSecretDecryptor = SuccessfulDecryptor(),
        channel: CredentialChannel = CredentialChannel.ENV,
        attachDependencies: Boolean = true,
        config: TerminalSessionsConfig = testTerminalConfig(),
        agentHelper: String = AGENT_HELPER,
        vaultHelper: String = VAULT_HELPER,
        shutdownGraceMillis: Long = 2_000L,
        private val secretTreeFactory: SecretTreeFactory = TempTreeFactory(secretRoot),
        private val idGenerator: () -> String = { FIXED_SESSION_ID },
    ) {
        /** Cache holding one profile and one encrypted secret. */
        val store = InMemoryWorkerCredentialStore().apply {
            replaceAll(
                profiles = listOf(profile(channel = channel)),
                secrets = listOf(secret(id = CACHED_SECRET_ID)),
            )
        }

        /** Manager under test. */
        val manager: TerminalSessionManager

        init {
            val resolution = CommandIsolationResolution.isolated(
                helperPath = agentHelper,
                user = "agent",
                home = "/home/agent",
                logger = logger,
                secretAccount = SecretCommandAccount.create(
                    helperPath = vaultHelper,
                    user = "vault",
                    home = "/home/vault",
                    logger = logger,
                ),
            )
            manager = TerminalSessionManager(
                config = config,
                resolution = resolution,
                childEnvironment = object : ChildEnvironmentProvider {
                    override fun build(): Map<String, String> = mapOf("PATH" to "/usr/bin")
                },
                workspace = workspace,
                credentials = if (!attachDependencies) {
                    null
                } else {
                    CredentialAttachDependencies(
                        store = store,
                        decryptor = decryptor,
                        materialFactory = SecretMaterialFactory(
                            trees = secretTreeFactory,
                            askpassScriptPath = "/usr/local/bin/torvian-askpass",
                        ),
                        vaultEnvironment = object : ChildEnvironmentProvider {
                            override fun build(): Map<String, String> =
                                mapOf("PATH" to "/usr/bin", "HOME" to "/home/vault", "USER" to "vault")
                        },
                        secretWorkingDirectory = profileWork,
                    )
                },
                processFactory = factory,
                idGenerator = idGenerator,
                shutdownGraceMillis = shutdownGraceMillis,
                logger = logger,
            )
            managers += manager
        }

        /**
         * Starts a shell session and waits until it is quiet enough to attach.
         *
         * @return The new session id.
         */
        suspend fun startSession(): String {
            val snapshot = assertNotNull(manager.start("bash", emptyList(), null, 200, 20, 200).getOrNull())
            return snapshot.sessionId
        }

        /**
         * @return How many per-attach trees still exist under the temporary root.
         */
        fun remainingSecretTrees(): Int =
            Files.list(secretRoot).use { stream -> stream.count().toInt() }
    }

    /**
     * [SecretTreeFactory] writing into a temporary directory instead of through the helper.
     *
     * @property root Directory the per-attach trees are created under.
     */
    private class TempTreeFactory(private val root: Path) : SecretTreeFactory {
        override fun create(
            fileName: String,
            value: SecretValue,
            deadline: Long,
        ): Either<SecretMaterialError, SecretTree> {
            val directory = root.resolve("tree-${System.nanoTime()}")
            Files.createDirectories(directory)
            val file = directory.resolve(fileName)
            Files.write(file, value.copyBytes())
            return TempTree(directory.toString(), file.toString()).right()
        }

        override fun clear(directoryPath: String, deadline: Long): Either<SecretMaterialError, Unit> {
            Path.of(directoryPath).toFile().deleteRecursively()
            return Unit.right()
        }
    }

    /**
     * @property directoryPath Directory holding the file.
     * @property secretPath File that was written.
     */
    private data class TempTree(
        override val directoryPath: String,
        override val secretPath: String,
    ) : SecretTree

    /** Decryptor that always succeeds with a fixed value. */
    private class SuccessfulDecryptor : CredentialSecretDecryptor {
        override fun decrypt(secret: EncryptedWorkerSecret): Either<SecretResolutionError, SecretValue> =
            SecretValue("token".toCharArray()).right()
    }

    /**
     * Decryptor that records every row it was asked to decrypt.
     *
     * Lets a test tell whether an operator change reached a target that was already running, or only the
     * attach that followed it.
     */
    private class RecordingDecryptor : CredentialSecretDecryptor {
        /** Rows decrypted so far, in call order. */
        val decrypted = CopyOnWriteArrayList<EncryptedWorkerSecret>()

        override fun decrypt(secret: EncryptedWorkerSecret): Either<SecretResolutionError, SecretValue> {
            decrypted += secret
            return SecretValue("token".toCharArray()).right()
        }
    }

    /**
     * Process factory handing out prepared fake processes in order.
     *
     * @property processes Processes to hand out, in order.
     */
    private class QueuedProcessFactory(vararg processes: FakeTerminalProcess) : TerminalProcessFactory {
        private val queue = ArrayDeque(processes.toList())
        private val specs = CopyOnWriteArrayList<TerminalProcessSpec>()

        /** Number of spawns attempted, including a failed one. */
        var startedCount: Int = 0
            private set

        /** One-based spawn index that must fail, or null when every spawn succeeds. */
        var failOnStart: Int? = null

        /** Last spec received, or null before the first attempt. */
        val lastSpec: TerminalProcessSpec?
            get() = specs.lastOrNull()

        override fun start(spec: TerminalProcessSpec): Process {
            startedCount++
            specs += spec
            if (failOnStart == startedCount) throw IOException("test spawn failure")
            return queue.removeFirst()
        }
    }

    /**
     * Process factory that can hold a spawn open until the test releases it.
     *
     * The hold happens after the process exists but before the caller records it, which is the state a
     * concurrent close or a cancellation has to survive.
     *
     * @property delegate Factory that actually creates the processes.
     */
    private class GatedProcessFactory(private val delegate: TerminalProcessFactory) : TerminalProcessFactory {
        private val spawned = CountDownLatch(1)
        private val released = CountDownLatch(1)

        @Volatile
        private var armed = false

        /** Makes every later spawn hold after its process was created. */
        fun arm() {
            armed = true
        }

        /**
         * Waits until a held spawn reached its process.
         *
         * @param timeoutMillis Maximum wait in milliseconds.
         * @return True when the spawn was reached before the timeout.
         */
        fun awaitSpawn(timeoutMillis: Long): Boolean = spawned.await(timeoutMillis, TimeUnit.MILLISECONDS)

        /** Releases the held spawn. */
        fun release() {
            released.countDown()
        }

        override fun start(spec: TerminalProcessSpec): Process {
            val process = delegate.start(spec)
            if (!armed) return process
            spawned.countDown()
            // Bounded so a test that never releases the gate fails instead of hanging the suite.
            released.await(10, TimeUnit.SECONDS)
            return process
        }
    }

    /**
     * Stub helpers that record their argv, so a test can tell which account's helper was run.
     *
     * @property directory Directory holding the stubs and their log.
     */
    private class RecordingHelpers(private val directory: Path) {
        /** Path of the command account's helper stub. */
        val agentHelper: String = directory.resolve("run-as-agent").toString()

        /** Path of the credential account's helper stub. */
        val vaultHelper: String = directory.resolve("run-as-vault").toString()

        /** File every stub appends its invocation to. */
        private val log: Path = directory.resolve("helper-calls.log")

        /**
         * Writes both stubs and makes them executable.
         *
         * @return This recorder, or null when the host cannot run a shell script.
         */
        fun install(): RecordingHelpers? = runCatching {
            listOf(agentHelper to "agent", vaultHelper to "vault").forEach { (path, name) ->
                Files.writeString(Path.of(path), "#!/bin/sh\necho \"$name \$*\" >> '$log'\nexit 0\n")
                Files.setPosixFilePermissions(Path.of(path), PosixFilePermissions.fromString("rwx------"))
            }
            this
        }.getOrNull()

        /**
         * @return Every recorded invocation, one per line; empty when no helper ran.
         */
        fun recordedCalls(): List<String> =
            runCatching { Files.readAllLines(log) }.getOrDefault(emptyList()).filter { it.isNotBlank() }
    }

    /**
     * @return Recording helpers installed in a temporary directory, or null when this host cannot run a
     *   shell script as a helper stub.
     */
    private fun recordingHelpers(): RecordingHelpers? {
        val directory = runCatching { Files.createTempDirectory("terminal-helper-stubs") }.getOrNull() ?: return null
        helperDirectories.add(directory)
        return RecordingHelpers(directory).install()
    }

    private companion object {
        /** Path the credential launcher is configured with. */
        const val VAULT_HELPER = "/usr/local/bin/run-as-vault"

        /** Path the command launcher is configured with. */
        const val AGENT_HELPER = "/usr/local/bin/run-as-agent"

        /** Attempts allowed while waiting for the session to observe a shell exit. */
        const val SHELL_EXIT_ATTEMPTS = 200

        /**
         * Polls until the session reports the shell as ended.
         *
         * @param manager Live manager.
         * @param sessionId Session whose shell exit is awaited.
         * @return True when the exit was observed within the bounded number of attempts.
         */
        suspend fun awaitShellExit(manager: TerminalSessionManager, sessionId: String): Boolean {
            repeat(SHELL_EXIT_ATTEMPTS) {
                if (manager.shellHasExited(sessionId)) return true
                delay(10.milliseconds)
            }
            return false
        }

        /** Reference the cached secret uses. */
        const val SECRET_REF = "vps_password"

        /** Server-side id of the profile the fixture caches. */
        const val PROFILE_ID = 1L

        /** Server-side id of the secret the fixture caches at bootstrap. */
        const val CACHED_SECRET_ID = 2L

        /** Server-side id of the row a test substitutes for [CACHED_SECRET_ID]. */
        const val REPLACED_SECRET_ID = 5L

        /**
         * @param id Server-side identifier of the encrypted row.
         * @param reference Reference a profile resolves the row by.
         * @return An encrypted secret as the server relays it; no plaintext is involved.
         */
        fun secret(
            id: Long,
            reference: String = SECRET_REF,
        ): EncryptedWorkerSecret = EncryptedWorkerSecret(
            id = id,
            workerId = 7,
            reference = reference,
            encryptedSecret = "ciphertext",
            encryptedDek = "dek",
            algorithm = "RSA-OAEP-256+A256GCM",
            workerCertificateFingerprint = "fingerprint",
        )

        /** Session id the fixture hands out unless a test needs several sessions. */
        const val FIXED_SESSION_ID = "aB3xY9"

        /**
         * @return An id generator handing out a fresh six-character id per call.
         */
        fun counterIds(): () -> String {
            val issued = AtomicInteger(0)
            return { "aB3x${issued.incrementAndGet().toString().padStart(2, '0')}" }
        }

        /**
         * @param channel Channel the cached profile uses.
         * @return Profile bound to this test's secret.
         */
        fun profile(channel: CredentialChannel): CredentialProfile = CredentialProfile(
            id = 1,
            workerId = 7,
            name = "vps",
            description = "Test profile",
            secretRef = SECRET_REF,
            command = "/usr/bin/env",
            args = listOf("id"),
            channel = channel,
            envName = "GH_TOKEN",
            fileName = "credentials.json",
            askpassUsername = null,
            isEnabled = true,
            environmentVariables = mapOf("PROFILE_HINT" to "1"),
            askpassVariables = emptyList(),
        )
    }
}
