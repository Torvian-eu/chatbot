package eu.torvian.chatbot.worker.credential

import eu.torvian.chatbot.worker.config.CredentialsConfig
import eu.torvian.chatbot.worker.config.WorkerConfigError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Verifies the startup checks for the secret root and the profile working directory.
 *
 * The checks are POSIX-shaped, so the cases that depend on ownership and mode bits are skipped on a
 * host without a POSIX view; the shape checks still run everywhere.
 */
class CredentialEnvironmentValidatorTest {

    /** Logger used by the validator; the test asserts results rather than log lines. */
    private val logger: Logger = LogManager.getLogger(CredentialEnvironmentValidatorTest::class.java)

    /** Temporary directory holding both directories under test. */
    private val root: Path = createTempDirectory("credential-environment-test")

    /** Removes the temporary root once a test finishes. */
    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    /** Verifies that a missing secret root is created and a correct working directory is accepted. */
    @Test
    fun `creates the secret root and accepts a usable working directory`() {
        val working = workingDirectory()
        val credentials = credentials(secretDir = root.resolve("secrets").toString(), working = working.toString())

        val result = CredentialEnvironmentValidator(credentials).validate(logger)

        assertTrue(result.isRight(), "Unexpected failure: ${result.swap().getOrNull()}")
        assertTrue(Files.isDirectory(root.resolve("secrets")), "The secret root was not created")
    }

    /** Verifies that the working directory must already exist. */
    @Test
    fun `rejects a missing working directory`() {
        val credentials = credentials(
            secretDir = root.resolve("secrets").toString(),
            working = root.resolve("missing-work").toString(),
        )

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(credentials).validate(logger).swap().getOrNull()
        )

        assertTrue(error.reason.contains("missing-work"), "Unexpected reason: ${error.reason}")
    }

    /** Verifies that the secret root must be a directory. */
    @Test
    fun `rejects a secret root that is not a directory`() {
        val secretRoot = root.resolve("secrets-file")
        Files.writeString(secretRoot, "")
        val credentials = credentials(
            secretDir = secretRoot.toString(),
            working = workingDirectory().toString(),
        )

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(credentials).validate(logger).swap().getOrNull()
        )

        assertTrue(error.reason.contains("not a directory"), "Unexpected reason: ${error.reason}")
    }

    /**
     * Verifies that a working directory owned by another account is rejected.
     *
     * A profile that loads configuration from its working directory would otherwise execute files the
     * worker account can write.
     */
    @Test
    fun `rejects a working directory owned by another account`() {
        if (isWindows) return
        val working = workingDirectory()

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = root.resolve("secrets").toString(),
                    working = working.toString(),
                    vaultUser = "another-account",
                )
            ).validate(logger).swap().getOrNull()
        )

        assertTrue(error.reason.contains("owned by"), "Unexpected reason: ${error.reason}")
    }

    /** Verifies that a working directory writable by a group is rejected. */
    @Test
    fun `rejects a group writable working directory`() {
        if (isWindows) return
        val working = workingDirectory()
        Files.setPosixFilePermissions(
            working,
            PosixFilePermissions.fromString("rwxrwx--x")
        )

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = root.resolve("secrets").toString(),
                    working = working.toString(),
                )
            ).validate(logger).swap().getOrNull()
        )

        assertTrue(error.reason.contains("writable by a group"), "Unexpected reason: ${error.reason}")
    }

    /** Verifies that a secret root readable by another account is rejected. */
    @Test
    fun `rejects a secret root readable by another account`() {
        if (isWindows) return
        val secretRoot = root.resolve("secrets").createDirectory()
        Files.setPosixFilePermissions(secretRoot, PosixFilePermissions.fromString("rwxr-xr-x"))

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = secretRoot.toString(),
                    working = workingDirectory().toString(),
                )
            ).validate(logger).swap().getOrNull()
        )

        assertTrue(
            error.reason.contains("readable or writable by an account"),
            "Unexpected reason: ${error.reason}",
        )
    }

    /**
     * Verifies that a group read bit on the secret root is rejected as well.
     *
     * Listing the per-attach trees is the disclosure the check exists to prevent, and the group of the
     * secret root is not verified to be the worker's own, so any group read bit is refused. Traversing
     * the root for others stays allowed: a profile has to reach the file inside its own tree.
     */
    @Test
    fun `rejects a secret root readable by its group`() {
        if (isWindows) return
        val secretRoot = root.resolve("secrets").createDirectory()
        Files.setPosixFilePermissions(secretRoot, PosixFilePermissions.fromString("rwxr-x---"))

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = secretRoot.toString(),
                    working = workingDirectory().toString(),
                )
            ).validate(logger).swap().getOrNull()
        )

        assertTrue(
            error.reason.contains("readable or writable by an account"),
            "Unexpected reason: ${error.reason}",
        )
    }

    /** Verifies that an absent askpass helper fails startup instead of the first attach. */
    @Test
    fun `rejects a missing askpass helper`() {
        val missing = root.resolve("missing-askpass")

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = root.resolve("secrets").toString(),
                    working = workingDirectory().toString(),
                    askpassScriptPath = missing.toString(),
                )
            ).validate(logger).swap().getOrNull()
        )

        assertTrue(
            error.reason.contains("askpassScriptPath"),
            "Unexpected reason: ${error.reason}",
        )
    }

    /** Verifies that an askpass helper that is not executable fails startup. */
    @Test
    fun `rejects an askpass helper that cannot be executed`() {
        if (isWindows) return
        val script = root.resolve("askpass-not-executable")
        Files.writeString(script, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-------"))

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = root.resolve("secrets").toString(),
                    working = workingDirectory().toString(),
                    askpassScriptPath = script.toString(),
                )
            ).validate(logger).swap().getOrNull()
        )

        assertTrue(
            error.reason.contains("askpassScriptPath"),
            "Unexpected reason: ${error.reason}",
        )
    }

    /**
     * Verifies that a secret root whose mode cannot be read fails startup.
     *
     * The check exists to refuse a permissive directory, so an unknown mode must not be treated as an
     * empty permission set: that would let a root whose mode cannot be inspected start the worker.
     */
    @Test
    fun `rejects a secret root whose mode cannot be read`() {
        if (isWindows) return
        val secretRoot = root.resolve("secrets").createDirectory()

        val error = assertIs<WorkerConfigError.CredentialsUnavailable>(
            CredentialEnvironmentValidator(
                credentials(
                    secretDir = secretRoot.toString(),
                    working = workingDirectory().toString(),
                )
            ) { path ->
                if (path == secretRoot) throw IOException("mode unavailable") else Files.getPosixFilePermissions(path)
            }.validate(logger).swap().getOrNull()
        )

        assertTrue(error.reason.contains("could not be read"), "Unexpected reason: ${error.reason}")
    }

    /** Verifies that a traversable secret root with no read or write access is accepted. */
    @Test
    fun `accepts a traversable secret root`() {
        if (isWindows) return
        val secretRoot = root.resolve("secrets").createDirectory()
        Files.setPosixFilePermissions(secretRoot, PosixFilePermissions.fromString("rwx--x--x"))

        val result = CredentialEnvironmentValidator(
            credentials(
                secretDir = secretRoot.toString(),
                working = workingDirectory().toString(),
            )
        ).validate(logger)

        assertTrue(result.isRight(), "Unexpected failure: ${result.swap().getOrNull()}")
    }

    /**
     * @return A working directory owned by the account the credentials are configured with.
     */
    private fun workingDirectory(): Path {
        val directory = root.resolve("work").createDirectory()
        return directory
    }

    /**
     * Account this test process runs as, which owns every directory it creates.
     *
     * Read from a file the test creates rather than from a property, so the value is the one the
     * filesystem reports and matches the ownership the validator compares against.
     */
    private val currentAccount: String by lazy {
        val probe = root.resolve("owner-probe")
        Files.createFile(probe)
        Files.getOwner(probe).name
    }

    /**
     * @param secretDir Secret root to configure.
     * @param working Profile working directory to configure.
     * @param vaultUser Account to configure, defaulting to whoever owns the temporary tree.
     * @param askpassScriptPath Askpass helper to configure.
     * @return Credentials for the supplied paths.
     */
    private fun credentials(
        secretDir: String,
        working: String,
        vaultUser: String? = null,
        askpassScriptPath: String = askpassScript.toString(),
    ): CredentialsConfig = CredentialsConfig(
        // The account is read from the host so the success cases exercise the ownership check rather
        // than bypass it; the rejection cases configure a name no directory here carries.
        vaultUser = vaultUser ?: if (isWindows) "vault" else currentAccount,
        vaultHelperPath = "/usr/local/bin/run-as-vault",
        askpassScriptPath = askpassScriptPath,
        secretDir = secretDir,
        secretWorkingDirectory = working,
        maxSecretBytes = 8192,
    )

    /**
     * Executable askpass stub the success cases point the configuration at.
     *
     * A real script is used rather than the image's path so the check is exercised on the host that
     * runs the test; the file is created once per test instance under the temporary root.
     */
    private val askpassScript: Path by lazy {
        val script = root.resolve("torvian-askpass")
        Files.writeString(script, "#!/bin/sh\nexit 0\n")
        runCatching { Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------")) }
        script
    }

    private companion object {
        /** Whether the current test host has no POSIX ownership view. */
        val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
