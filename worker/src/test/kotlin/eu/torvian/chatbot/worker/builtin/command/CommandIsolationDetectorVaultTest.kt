package eu.torvian.chatbot.worker.builtin.command

import eu.torvian.chatbot.worker.config.CommandEnvironmentConfig
import eu.torvian.chatbot.worker.config.CommandIsolationConfig
import eu.torvian.chatbot.worker.config.CredentialsConfig
import eu.torvian.chatbot.worker.config.WorkerConfigError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Verifies how the detector resolves the second, credential-bearing account.
 *
 * The helper probes and the platform check are injected, so every row of the decision table is
 * exercised without a Linux host, a setuid helper or any child process.
 */
class CommandIsolationDetectorVaultTest {

    /** Logger used by the detector under test; results are asserted rather than log lines. */
    private val logger: Logger = LogManager.getLogger(CommandIsolationDetectorVaultTest::class.java)

    /** Probe answering with one account per helper path, so agent and vault are distinguishable. */
    private val twoAccountProbe: (CommandIsolationConfig) -> HelperProbeResult = { probed ->
        when (probed.helperPath) {
            VAULT_HELPER_PATH ->
                HelperProbeResult.EffectiveUser(VAULT_USER, VAULT_USER_ID, VAULT_HOME)

            else -> HelperProbeResult.EffectiveUser(AGENT_USER, AGENT_USER_ID, AGENT_HOME)
        }
    }

    /** Verifies that a missing credential helper fails startup when isolation is enabled. */
    @Test
    fun `missing credential helper fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = isolation(),
            credentials = credentials(),
            logger = logger,
            probe = { probed ->
                if (probed.helperPath == VAULT_HELPER_PATH) {
                    HelperProbeResult.Failed("Credential helper $VAULT_HELPER_PATH does not exist")
                } else {
                    twoAccountProbe(probed)
                }
            },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, VAULT_HELPER_PATH)
    }

    /** Verifies that a credential helper reporting another account fails startup. */
    @Test
    fun `credential helper reporting another account fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = isolation(),
            credentials = credentials(),
            logger = logger,
            probe = { probed ->
                if (probed.helperPath == VAULT_HELPER_PATH) {
                    HelperProbeResult.EffectiveUser(AGENT_USER, VAULT_USER_ID, VAULT_HOME)
                } else {
                    twoAccountProbe(probed)
                }
            },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, AGENT_USER)
        assertContains(error.reason, VAULT_USER)
    }

    /** Verifies that a credential account sharing the worker's user id fails startup. */
    @Test
    fun `credential account sharing the worker user id fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = isolation(),
            credentials = credentials(),
            logger = logger,
            probe = { probed ->
                if (probed.helperPath == VAULT_HELPER_PATH) {
                    HelperProbeResult.EffectiveUser(VAULT_USER, WORKER_USER_ID, VAULT_HOME)
                } else {
                    twoAccountProbe(probed)
                }
            },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, WORKER_USER_ID.toString())
    }

    /**
     * Verifies that a credential account sharing the isolated command account's user id fails startup.
     *
     * That collision is the one the second account exists to prevent: a shell the model controls would
     * otherwise run with the credentials a profile target has loaded.
     */
    @Test
    fun `credential account sharing the agent user id fails startup`() {
        val result = CommandIsolationDetector.resolve(
            config = isolation(),
            credentials = credentials(),
            logger = logger,
            probe = { probed ->
                if (probed.helperPath == VAULT_HELPER_PATH) {
                    HelperProbeResult.EffectiveUser(VAULT_USER, AGENT_USER_ID, VAULT_HOME)
                } else {
                    twoAccountProbe(probed)
                }
            },
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val error = assertIs<WorkerConfigError.CommandIsolationUnavailable>(result.swap().getOrNull())
        assertContains(error.reason, AGENT_USER_ID.toString())
        assertContains(error.reason, AGENT_USER)
    }

    /** Verifies that the resolved credential account carries its launcher, terminator and home. */
    @Test
    fun `resolved credential account exposes the vault identity`() {
        val result = CommandIsolationDetector.resolve(
            config = isolation(),
            credentials = credentials(),
            logger = logger,
            probe = twoAccountProbe,
            isLinux = true,
            ownUserId = { WORKER_USER_ID },
        )

        val secretAccount = assertIs<SecretCommandAccount>(result.getOrNull()?.secretAccount)
        assertEquals(VAULT_USER, secretAccount.user)
        assertEquals(VAULT_HOME, secretAccount.home)
        // The launcher must address the credential helper, not the one ordinary commands use.
        assertEquals(
            listOf(VAULT_HELPER_PATH, "id", "-un"),
            secretAccount.launcher.wrap("id", listOf("-un")),
        )
    }

    /** Verifies that a platform without the mechanism resolves no credential account. */
    @Test
    fun `unsupported platform resolves no credential account`() {
        val result = CommandIsolationDetector.resolve(
            config = isolation(),
            credentials = credentials(),
            logger = logger,
            probe = { throw AssertionError("The helper probe must not run on this platform") },
            isLinux = false,
            ownUserId = { throw AssertionError("The user id must not be read on this platform") },
        )

        assertNull(result.getOrNull()?.secretAccount)
    }

    /**
     * @return Isolation configuration whose command account is the agent account.
     */
    private fun isolation(): CommandIsolationConfig = CommandIsolationConfig(
        enabled = true,
        user = AGENT_USER,
        helperPath = "/usr/local/bin/run-as-agent",
        environment = CommandEnvironmentConfig(listOf("PATH"), set = emptyMap()),
    )

    /**
     * @return Credential deployment configuration naming the vault account and helper.
     */
    private fun credentials(): CredentialsConfig = CredentialsConfig(
        vaultUser = VAULT_USER,
        vaultHelperPath = VAULT_HELPER_PATH,
        askpassScriptPath = "/usr/local/bin/torvian-askpass",
        secretDir = "/run/torvian/secrets",
        secretWorkingDirectory = VAULT_HOME + "/work",
        maxSecretBytes = 8192,
    )

    private companion object {
        /** Account name ordinary built-in-tool commands run as. */
        const val AGENT_USER = "agent"

        /** User id of [AGENT_USER] in these cases. */
        const val AGENT_USER_ID = 10002L

        /** Home directory of [AGENT_USER] in these cases. */
        const val AGENT_HOME = "/home/agent"

        /** Account name credential profile targets run as. */
        const val VAULT_USER = "vault"

        /** User id of [VAULT_USER] in these cases. */
        const val VAULT_USER_ID = 10004L

        /** Home directory of [VAULT_USER] in these cases. */
        const val VAULT_HOME = "/home/vault"

        /** Helper path the credential cases assume. */
        const val VAULT_HELPER_PATH = "/usr/local/bin/run-as-vault"

        /** User id the worker process is assumed to run with. */
        const val WORKER_USER_ID = 10001L
    }
}
