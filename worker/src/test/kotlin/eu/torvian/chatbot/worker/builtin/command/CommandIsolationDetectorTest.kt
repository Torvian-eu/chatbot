package eu.torvian.chatbot.worker.builtin.command

import eu.torvian.chatbot.worker.config.CommandEnvironmentConfig
import eu.torvian.chatbot.worker.config.CommandIsolationConfig
import eu.torvian.chatbot.worker.config.CredentialsConfig
import eu.torvian.chatbot.worker.config.WorkerConfigError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the startup decision table of the command-isolation detector.
 *
 * The probe and the platform check are injected so the whole table is exercised without a Linux
 * host, a setuid helper or any child process.
 */
class CommandIsolationDetectorTest {

    /** Logger used by the detector under test; the test observes results rather than log output. */
    private val logger: Logger = LogManager.getLogger(CommandIsolationDetectorTest::class.java)

    /** Probe that fails the test when invoked, used to prove detection short-circuits. */
    private val forbiddenProbe: (CommandIsolationConfig) -> HelperProbeResult = {
        throw AssertionError("The helper probe must not run in this state")
    }

    /** User-id provider that fails the test when invoked, used to prove the numeric check is skipped. */
    private val forbiddenUserId: () -> Long? = {
        throw AssertionError("The worker's own user id must not be read in this state")
    }

    /** Verifies that a platform without the mechanism resolves to the no-op launcher. */
    @Test
    fun `unsupported platform does not attempt isolation`() {
        val result = CommandIsolationDetector.resolve(
            config = config(),
            credentials = credentials(),
            logger = logger,
            probe = forbiddenProbe,
            isLinux = false,
            ownUserId = forbiddenUserId,
        )

        val resolution = result.getOrNull()
        assertIs<NoopLauncher>(resolution?.launcher)
        assertNull(resolution?.isolatedUser)
        // Without a privilege drop there is no account a credential could be loaded into.
        assertNull(resolution?.secretAccount)
    }

    /** Verifies that disabling isolation skips the probe entirely. */
    @Test
    fun `disabled isolation does not attempt isolation`() {
        val result = CommandIsolationDetector.resolve(
            config = config(enabled = false),
            credentials = credentials(),
            logger = logger,
            probe = forbiddenProbe,
            isLinux = true,
            ownUserId = forbiddenUserId,
        )

        val resolution = result.getOrNull()
        assertIs<NoopLauncher>(resolution?.launcher)
        assertNull(resolution?.isolatedUser)
        // Credential availability follows isolation, so no vault account is resolved here either.
        assertNull(resolution?.secretAccount)
    }

    /** Verifies that a working helper activates isolation for the configured account. */
    @Test
    fun `working helper resolves to the helper launcher`() {
        val config = config()

        val result = CommandIsolationDetector.resolve(
            config = config,
            credentials = credentials(),
            logger = logger,
            // Both helpers are probed once isolation is on, so the stub answers for whichever path
            // the detector asks about.
            probe = { probed ->
                if (probed.helperPath == VAULT_HELPER_PATH) {
                    HelperProbeResult.EffectiveUser(VAULT_USER, VAULT_USER_ID, "/home/vault")
                } else {
                    HelperProbeResult.EffectiveUser("agent", ISOLATED_USER_ID, "/opt/agent-home")
                }
            },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val resolution = result.getOrNull()
        assertIs<SetuidHelperLauncher>(resolution?.launcher)
        assertEquals(listOf(config.helperPath, "pwd"), resolution?.launcher?.wrap("pwd", emptyList()))
        assertEquals("agent", resolution?.isolatedUser)
        // The account's home travels with the account so the child's HOME matches its real record.
        assertEquals("/opt/agent-home", resolution?.isolatedHome)
        // The credential account is resolved alongside it, so consumers never derive it themselves.
        assertEquals("vault", resolution?.secretAccount?.user)
        assertEquals("/home/vault", resolution?.secretAccount?.home)
    }

    /** Verifies that an account mismatch fails startup and names both accounts. */
    @Test
    fun `reported account mismatch fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = config(user = "agent"),
            credentials = credentials(),
            logger = logger,
            probe = { HelperProbeResult.EffectiveUser("torvian", WORKER_USER_ID, "/home/torvian") },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, "torvian")
        assertContains(error.reason, "agent")
    }

    /**
     * Verifies that a helper which reports the worker's own user id fails startup.
     *
     * Two account names can share one user id, and a name comparison alone would accept the helper.
     */
    @Test
    fun `helper running commands as the worker user id fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = config(user = "agent"),
            credentials = credentials(),
            logger = logger,
            probe = { HelperProbeResult.EffectiveUser("agent", WORKER_USER_ID, "/home/agent") },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, WORKER_USER_ID.toString())
        assertContains(error.reason, "worker.commandIsolation.enabled = false")
    }

    /** Verifies that an unreadable worker user id fails startup instead of skipping the check. */
    @Test
    fun `unreadable worker user id fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = config(),
            credentials = credentials(),
            logger = logger,
            probe = { HelperProbeResult.EffectiveUser("agent", ISOLATED_USER_ID, "/home/agent") },
            isLinux = true,
            ownUserId = { null },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, "own user id")
    }

    /** Verifies that a failed probe while isolation is required fails startup with its reason. */
    @Test
    fun `failed probe fails startup carrying the probe reason`() {
        val result = CommandIsolationDetector.resolve(
            config = config(),
            credentials = credentials(),
            logger = logger,
            probe = { HelperProbeResult.Failed("helper is not executable") },
            isLinux = true,
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, "helper is not executable")
        assertContains(error.reason, "worker.commandIsolation.enabled = false")
    }

    /** Verifies that the real probe reports a clear failure when no helper exists at the path. */
    @Test
    fun `probe reports a missing helper`() {
        val missingHelperPath = createTempDirectory("command-isolation-test")
            .resolve("run-as-agent")
            .toString()

        val result = CommandIsolationDetector.probeHelper(config(helperPath = missingHelperPath))

        val failure = assertIs<HelperProbeResult.Failed>(result)
        assertTrue(failure.reason.contains(missingHelperPath), "Unexpected reason: ${failure.reason}")
    }

    /** Verifies that a helper that exists without an executable bit is reported as such. */
    @Test
    fun `probe reports a non executable helper`() {
        if (isWindows) return
        val dir = createTempDirectory("command-isolation-test")
        try {
            val stub = dir.resolve("run-as-agent")
            stub.writeText("#!/bin/sh\nexit 0\n")
            assertTrue(stub.toFile().setExecutable(false), "Could not clear the executable bit")

            val failure = assertIs<HelperProbeResult.Failed>(
                CommandIsolationDetector.probeHelper(config(helperPath = stub.toString()))
            )

            assertContains(failure.reason, "not executable")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that a failing helper is reported with its exit code and its own stderr line. */
    @Test
    fun `probe carries the helper stderr line and exit code`() {
        if (isWindows) return
        val dir = createTempDirectory("command-isolation-test")
        try {
            val stub = writeStub(
                dir,
                "#!/bin/sh\necho 'run-as-agent: setuid: Operation not permitted' >&2\nexit 6\n"
            )

            val failure = assertIs<HelperProbeResult.Failed>(
                CommandIsolationDetector.probeHelper(config(helperPath = stub.toString()))
            )

            assertContains(failure.reason, "exited with code 6")
            assertContains(failure.reason, "setuid: Operation not permitted")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that a hung helper is reported without a cause the probe never observed. */
    @Test
    fun `probe timeout reports an undifferentiated reason`() {
        if (isWindows) return
        val dir = createTempDirectory("command-isolation-test")
        try {
            val stub = writeStub(dir, "#!/bin/sh\nsleep 30\n")

            val failure = assertIs<HelperProbeResult.Failed>(
                CommandIsolationDetector.probeHelper(config(helperPath = stub.toString()), timeoutSeconds = 1)
            )

            assertContains(failure.reason, "did not respond within")
            assertFalse(failure.reason.contains("nosuid"), "Probe guessed at a filesystem cause")
            assertFalse(failure.reason.contains("setuid"), "Probe guessed at a setuid cause")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that the real probe and the real decision path report an unusable helper. */
    @Test
    fun `missing helper fails resolution with the operator hint`() {
        val missingHelperPath = createTempDirectory("command-isolation-test")
            .resolve("run-as-agent")
            .toString()

        val result = CommandIsolationDetector.resolve(
            config = config(helperPath = missingHelperPath),
            credentials = credentials(),
            logger = logger,
            isLinux = true,
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, missingHelperPath)
        assertContains(error.reason, "worker.commandIsolation.enabled = false")
    }

    /**
     * Verifies that the real probe reports the account's home directory from the account database.
     */
    @Test
    fun `probe reports the account home from the database`() {
        if (isWindows) return
        val dir = createTempDirectory("command-isolation-test")
        try {
            // The stub stands in for the helper and reports the account the probe would observe.
            val stub = writeStub(dir, "#!/bin/sh\nid\n")

            val reported = assertIs<HelperProbeResult.EffectiveUser>(
                CommandIsolationDetector.probeHelper(config(helperPath = stub.toString()))
            )

            assertEquals(accountHome(reported.user), reported.home)
            assertTrue(reported.uid > 0, "Probe reported no user id: ${reported.uid}")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that the probe reads the account and its user id from the helper's own report. */
    @Test
    fun `probe reads the account and user id from the helper output`() {
        if (isWindows) return
        val dir = createTempDirectory("command-isolation-test")
        try {
            val stub = writeStub(
                dir,
                "#!/bin/sh\necho 'uid=4242(reported-account) gid=4242(shared) groups=4242(shared)'\n"
            )

            val reported = assertIs<HelperProbeResult.EffectiveUser>(
                CommandIsolationDetector.probeHelper(config(helperPath = stub.toString()))
            )

            assertEquals("reported-account", reported.user)
            assertEquals(4242L, reported.uid)
            // An account the database does not know keeps the image's layout as its home.
            assertEquals("/home/reported-account", reported.home)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Verifies that helper output without an account line is reported instead of guessed at. */
    @Test
    fun `probe rejects output that carries no account`() {
        if (isWindows) return
        val dir = createTempDirectory("command-isolation-test")
        try {
            val stub = writeStub(dir, "#!/bin/sh\necho 'hello'\n")

            val failure = assertIs<HelperProbeResult.Failed>(
                CommandIsolationDetector.probeHelper(config(helperPath = stub.toString()))
            )

            assertContains(failure.reason, "uid=<user id>(<account>)")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Reads [user]'s home directory straight from the account file.
     *
     * @param user Account name whose home directory is expected.
     * @return The home directory recorded for the account, or `/home/<user>` when absent.
     */
    private fun accountHome(user: String): String {
        val record = java.io.File(PASSWD_FILE)
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("$user:") }
        return record?.split(':')?.getOrNull(PASSWD_HOME_FIELD_INDEX)?.takeIf { it.isNotBlank() }
            ?: "/home/$user"
    }

    /**
     * Writes an executable stub standing in for the helper and waits until it can be executed.
     *
     * A process forked while the stub was being written briefly holds its write descriptor, which
     * makes an immediate exec of that file fail with `ETXTBSY`; the window closes as soon as that
     * fork completes, so the stub is spawned once here before a probe assertion relies on it.
     *
     * @param dir Directory that receives the stub.
     * @param content Full script content, including the shebang.
     * @return Path of the executable stub.
     */
    private fun writeStub(dir: Path, content: String): Path {
        val stub = dir.resolve("run-as-agent")
        stub.writeText(content)
        assertTrue(stub.toFile().setExecutable(true), "Could not mark the stub executable")
        awaitExecutable(stub)
        return stub
    }

    /**
     * Retries until executing [stub] is no longer refused as busy.
     *
     * @param stub Executable file that must be runnable.
     */
    private fun awaitExecutable(stub: Path) {
        repeat(50) {
            try {
                val warmUp = ProcessBuilder(stub.toString()).apply { redirectErrorStream(true) }.start()
                warmUp.destroyForcibly()
                return
            } catch (e: IOException) {
                if (!e.message.orEmpty().contains("Text file busy")) throw e
                Thread.sleep(10)
            }
        }
        throw AssertionError("Stub $stub could not be executed")
    }

    /**
     * Builds an isolation configuration with values matching the shipped worker image.
     *
     * @param enabled Whether isolation is required on supported platforms.
     * @param user Account commands are expected to run as.
     * @param helperPath Helper path used by the probe and the launcher.
     * @return Configuration accepted by the detector.
     */
    private fun config(
        enabled: Boolean = true,
        user: String = "agent",
        helperPath: String = "/usr/local/bin/run-as-agent",
    ): CommandIsolationConfig = CommandIsolationConfig(
        enabled = enabled,
        user = user,
        helperPath = helperPath,
        environment = CommandEnvironmentConfig(listOf("PATH", "LANG", "LC_ALL"), set = emptyMap()),
    )

    /**
     * @return Credential configuration whose vault account is distinct from the agent account.
     */
    private fun credentials(): CredentialsConfig = CredentialsConfig(
        vaultUser = VAULT_USER,
        vaultHelperPath = VAULT_HELPER_PATH,
        askpassScriptPath = "/usr/local/bin/torvian-askpass",
        secretDir = "/run/torvian/secrets",
        secretWorkingDirectory = "/home/vault/work",
        maxSecretBytes = 8192,
    )

    private companion object {
        /** Whether the current test host cannot run an executable shell script. */
        val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)

        /** Account file the tests read to predict the probe's home-directory lookup. */
        const val PASSWD_FILE = "/etc/passwd"

        /** Colon-separated field of a passwd record that holds the account's home directory. */
        const val PASSWD_HOME_FIELD_INDEX = 5

        /** User id the worker process is assumed to run with in the decision-table cases. */
        const val WORKER_USER_ID = 10001L

        /** User id the isolated account is assumed to have in the decision-table cases. */
        const val ISOLATED_USER_ID = 10002L

        /** User id the credential account is assumed to have in the decision-table cases. */
        const val VAULT_USER_ID = 10004L

        /** Account name the credential cases assume for `worker.credentials.vaultUser`. */
        const val VAULT_USER = "vault"

        /** Helper path the credential cases assume for `worker.credentials.vaultHelperPath`. */
        const val VAULT_HELPER_PATH = "/usr/local/bin/run-as-vault"
    }
}
