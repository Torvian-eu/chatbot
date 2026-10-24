package eu.torvian.chatbot.worker.builtin.terminal.secret

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.worker.credential.CredentialProfile
import eu.torvian.chatbot.worker.credential.SecretMaterialError
import eu.torvian.chatbot.worker.credential.SecretTree
import eu.torvian.chatbot.worker.credential.SecretTreeFactory
import eu.torvian.chatbot.worker.credential.SecretValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the three delivery channels and the cleanup they own.
 *
 * The tree factory is a temporary directory the test account owns, so the channel logic is exercised
 * without a setuid helper or a specific OS account.
 */
class SecretMaterialTest {

    /** Root of the temporary secret trees created by this test class. */
    private val root: Path = createTempDirectory("secret-material-test")

    /** Removes the temporary root once a test finishes. */
    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private companion object {
        /** Monotonic deadline far enough away that the channel logic runs without a helper budget. */
        val DEADLINE: Long
            get() = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
    }

    /** Verifies that the askpass channel points every profile variable at the script. */
    @Test
    fun `askpass material sets the documented environment`() {
        val script = executableScript()
        val trees = TempDirTreeFactory(root)

        val material = assertIs<SecretMaterial>(
            AskpassSecretMaterial.create(
                trees = trees,
                askpassScriptPath = script.toString(),
                askpassVariables = listOf("SSH_ASKPASS", "GIT_ASKPASS"),
                askpassUsername = "x-access-token",
                value = value("hunter2"),
                deadline = DEADLINE,
            ).getOrNull()
        )

        assertEquals(script.toString(), material.environment["SSH_ASKPASS"])
        assertEquals(script.toString(), material.environment["GIT_ASKPASS"])
        assertEquals("x-access-token", material.environment[AskpassEnvironment.USERNAME_VARIABLE])

        val secretFile = material.environment.getValue(AskpassEnvironment.FILE_VARIABLE)
        assertTrue(Path.of(secretFile).exists(), "The askpass secret file was not created")
        assertEquals("hunter2", Path.of(secretFile).readText())

        material.close(DEADLINE)
        assertEquals(0, trees.remainingDirectories(), "The secret tree was not removed")
    }

    /** Verifies that the username answer is omitted when the profile does not set one. */
    @Test
    fun `askpass material omits the username answer when unset`() {
        val material = assertIs<SecretMaterial>(
            AskpassSecretMaterial.create(
                trees = TempDirTreeFactory(root),
                askpassScriptPath = executableScript().toString(),
                askpassVariables = listOf("GIT_ASKPASS"),
                askpassUsername = null,
                value = value("token"),
                deadline = DEADLINE,
            ).getOrNull()
        )

        assertNull(material.environment[AskpassEnvironment.USERNAME_VARIABLE])
        material.close(DEADLINE)
    }

    /** Verifies that a missing askpass script fails before anything is written. */
    @Test
    fun `askpass material rejects a missing script`() {
        val trees = TempDirTreeFactory(root)

        val error = assertIs<SecretMaterialError.PreparationFailed>(
            AskpassSecretMaterial.create(
                trees = trees,
                askpassScriptPath = root.resolve("missing-askpass").toString(),
                askpassVariables = listOf("SSH_ASKPASS"),
                askpassUsername = null,
                value = value("token"),
                deadline = DEADLINE,
            ).swap().getOrNull()
        )

        assertTrue(error.reason.contains("missing-askpass"), "Unexpected reason: ${error.reason}")
    }

    /** Verifies that an askpass profile without any variable is rejected. */
    @Test
    fun `askpass material requires at least one variable`() {
        assertIs<SecretMaterialError.PreparationFailed>(
            AskpassSecretMaterial.create(
                trees = TempDirTreeFactory(root),
                askpassScriptPath = executableScript().toString(),
                askpassVariables = emptyList(),
                askpassUsername = null,
                value = value("token"),
                deadline = DEADLINE,
            ).swap().getOrNull()
        )
    }

    /** Verifies that the env channel adds exactly one variable and creates no file. */
    @Test
    fun `env material adds one variable and no file`() {
        val material = assertIs<SecretMaterial>(
            EnvSecretMaterial.create(envName = "GH_TOKEN", value = value("t0ken")).getOrNull()
        )

        assertEquals(mapOf("GH_TOKEN" to "t0ken"), material.environment)

        material.close(DEADLINE)
        material.close(DEADLINE)
    }

    /** Verifies that an env profile without a variable name is rejected. */
    @Test
    fun `env material requires a variable name`() {
        assertIs<SecretMaterialError.PreparationFailed>(
            EnvSecretMaterial.create(envName = null, value = value("token")).swap().getOrNull()
        )
    }

    /** Verifies that the file channel publishes the created path and removes the file on close. */
    @Test
    fun `file material sets the path variable and cleans up`() {
        val trees = TempDirTreeFactory(root)

        val material = assertIs<SecretMaterial>(
            FileSecretMaterial.create(
                trees = trees,
                fileName = "credentials.json",
                envName = "GOOGLE_APPLICATION_CREDENTIALS",
                value = value("{\"type\":\"service_account\"}"),
                deadline = DEADLINE,
            ).getOrNull()
        )

        val path = material.environment.getValue("GOOGLE_APPLICATION_CREDENTIALS")
        // Compared as a file name: a separator literal would only hold on the platform it was written on.
        assertEquals("credentials.json", Path.of(path).fileName.toString(), "Unexpected path: $path")
        assertEquals("{\"type\":\"service_account\"}", Path.of(path).readText())

        material.close(DEADLINE)
        assertTrue(!Path.of(path).exists(), "The secret file was not removed")
    }

    /** Verifies that the file channel requires both a file name and a variable name. */
    @Test
    fun `file material requires a file name and a variable name`() {
        assertIs<SecretMaterialError.PreparationFailed>(
            FileSecretMaterial.create(
                trees = TempDirTreeFactory(root),
                fileName = null,
                envName = "VAR",
                value = value("token"),
                deadline = DEADLINE,
            ).swap().getOrNull()
        )
        assertIs<SecretMaterialError.PreparationFailed>(
            FileSecretMaterial.create(
                trees = TempDirTreeFactory(root),
                fileName = "credentials.json",
                envName = null,
                value = value("token"),
                deadline = DEADLINE,
            ).swap().getOrNull()
        )
    }

    /** Verifies that the factory dispatches on the profile's channel and creates no redactor. */
    @Test
    fun `factory builds the material the profile names`() {
        val script = executableScript().toString()
        val factory = SecretMaterialFactory(trees = TempDirTreeFactory(root), askpassScriptPath = script)

        val askpass = assertIs<SecretMaterial>(
            factory.create(profile(channel = CredentialChannel.ASKPASS), value("token"), DEADLINE)
                .getOrNull()
        )
        assertEquals(script, askpass.environment["SSH_ASKPASS"])
        askpass.close(DEADLINE)

        val env = assertIs<SecretMaterial>(
            factory.create(profile(channel = CredentialChannel.ENV), value("token"), DEADLINE).getOrNull()
        )
        assertEquals(mapOf("GH_TOKEN" to "token"), env.environment)
        env.close(DEADLINE)

        val file = assertIs<SecretMaterial>(
            factory.create(profile(channel = CredentialChannel.FILE), value("token"), DEADLINE).getOrNull()
        )
        assertEquals(1, file.environment.size)
        file.close(DEADLINE)
    }

    /**
     * @param text Value to wrap.
     * @return Secret value built from [text].
     */
    private fun value(text: String): SecretValue = SecretValue(text.toCharArray())

    /**
     * @return Path of a file that is executable on this host.
     */
    private fun executableScript(): Path {
        val script = root.resolve("torvian-askpass")
        Files.writeString(script, "#!/bin/sh\nexit 0\n")
        assertTrue(script.toFile().setExecutable(true), "Could not make the askpass stub executable")
        return script
    }

    /**
     * Builds a profile with the channel-specific fields the factory reads.
     *
     * @param channel Channel to build for.
     * @return Profile whose non-secret fields describe that channel.
     */
    private fun profile(channel: CredentialChannel): CredentialProfile = CredentialProfile(
        id = 1,
        workerId = 7,
        name = "profile",
        description = null,
        secretRef = "ref",
        command = "/usr/bin/env",
        args = emptyList(),
        channel = channel,
        envName = "GH_TOKEN",
        fileName = "credentials.json",
        askpassUsername = "git",
        isEnabled = true,
        environmentVariables = emptyMap(),
        askpassVariables = listOf("SSH_ASKPASS"),
    )

    /**
     * [SecretTreeFactory] backed by a temporary directory instead of the credential helper.
     *
     * @property root Directory the per-attach trees are created under.
     */
    private class TempDirTreeFactory(private val root: Path) : SecretTreeFactory {
        /** Directories created so far, so a test can assert they were removed. */
        private val created = mutableListOf<Path>()

        override fun create(
            fileName: String,
            value: SecretValue,
            deadline: Long,
        ): Either<SecretMaterialError, SecretTree> {
            val directory = root.resolve("tree-${created.size}")
            Files.createDirectories(directory)
            val file = directory.resolve(fileName)
            Files.write(file, value.copyBytes())
            created.add(directory)
            return TempTree(directoryPath = directory.toString(), secretPath = file.toString()).right()
        }

        override fun clear(directoryPath: String, deadline: Long): Either<SecretMaterialError, Unit> {
            Path.of(directoryPath).toFile().deleteRecursively()
            return Unit.right()
        }

        /**
         * @return How many of the created directories still exist.
         */
        fun remainingDirectories(): Int = created.count { it.exists() }
    }

    /**
     * @property directoryPath Directory holding the file.
     * @property secretPath File that was written.
     */
    private data class TempTree(
        override val directoryPath: String,
        override val secretPath: String,
    ) : SecretTree
}
