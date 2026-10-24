package eu.torvian.chatbot.worker.credential

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Verifies the worker side of the credential-helper contract.
 *
 * The helper is the boundary the whole feature rests on, so two layers are checked separately: the
 * argv and exit-status contract `HelperSecretTreeFactory` must agree with `run-as-vault.c`, which is
 * host-independent, and the helper's own behaviour, which needs the installed setuid binary, the
 * `vault` account and a POSIX host and is therefore skipped elsewhere.
 *
 * The helper-mediated cases run a stub script in place of the binary: they prove the factory feeds the
 * value on stdin, classifies the exit status and keeps helper output out of results, without needing a
 * compiled helper.
 */
class SecretTreeHelperTest {

    /** Logger handed to the factory; the tests assert on results rather than log lines. */
    private val logger: Logger = LogManager.getLogger(SecretTreeHelperTest::class.java)

    /** Directories created by a test, removed once it finishes. */
    private val directories = mutableListOf<Path>()

    /** Removes every temporary directory a test created. */
    @AfterTest
    fun cleanUp() {
        directories.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    /**
     * Verifies the argv each secret mode passes, which must match the helper's exact-argc check.
     */
    @Test
    fun `secret modes pass the root and the target path after the mode`() {
        assertEquals(
            listOf("--write-secret", "/run/torvian/secrets", "/run/torvian/secrets/token/credentials.json"),
            HelperSecretTreeProtocol.writeSecretArguments(
                secretDir = "/run/torvian/secrets",
                secretPath = "/run/torvian/secrets/token/credentials.json",
            ),
        )
        assertEquals(
            listOf("--clear-secret-dir", "/run/torvian/secrets", "/run/torvian/secrets/token"),
            HelperSecretTreeProtocol.clearSecretDirArguments(
                secretDir = "/run/torvian/secrets",
                directoryPath = "/run/torvian/secrets/token",
            ),
        )
    }

    /**
     * Verifies that every documented exit status maps to a fixed sentence with no helper text.
     *
     * The helper writes absolute paths to stderr, so a status that has no classification would leak
     * them into a model-visible error.
     */
    @Test
    fun `helper exit statuses classify into fixed sentences`() {
        val statuses = listOf(2, 3, 8, 9, 10, 11, 12, 13, 14)

        val descriptions = statuses.map(HelperSecretTreeProtocol::describeExitStatus)

        descriptions.forEach { description ->
            assertTrue(description.isNotBlank(), "A status produced an empty description")
            assertFalse(description.contains('/'), "A description names a path: $description")
        }
        assertEquals(
            descriptions.size,
            descriptions.toSet().size,
            "Two statuses share one description: $descriptions",
        )
        assertEquals(
            "the helper failed",
            HelperSecretTreeProtocol.describeExitStatus(1),
            "An undocumented status must fall back to a generic sentence",
        )
    }

    /**
     * Verifies that a successful helper call receives the value on stdin and the argv of its mode.
     */
    @Test
    fun `create feeds the value on stdin and passes the write argv`() {
        val stub = recordingStubOrSkip()
        val secretDir = temporaryDirectory("secret-root").toString()

        val tree = assertIs<SecretTree>(
            HelperSecretTreeFactory(secretDir, stub.script.toString(), logger)
                .create("credentials.json", SecretValue("hunter2".toCharArray()), deadline())
                .getOrNull()
        )

        assertEquals(
            listOf(WRITE_SECRET_MODE, secretDir, tree.secretPath),
            stub.recordedArguments(),
        )
        assertEquals("hunter2", Files.readString(stub.stdin))
        assertEquals("credentials.json", Path.of(tree.secretPath).fileName.toString())
        assertEquals(Path.of(tree.secretPath).parent.toString(), tree.directoryPath)
    }

    /**
     * Verifies that a failing helper is reported by status only, never with its stderr text.
     */
    @Test
    fun `a failing helper is reported without its stderr text`() {
        val stub = recordingStubOrSkip(exitCode = 13, stderrLine = "run-as-vault: /run/torvian/secrets/t ok: no")
        val secretDir = temporaryDirectory("secret-root").toString()

        val error = assertIs<SecretMaterialError.PreparationFailed>(
            HelperSecretTreeFactory(secretDir, stub.script.toString(), logger)
                .clear("$secretDir/token", deadline())
                .swap().getOrNull()
        )

        assertTrue(
            error.reason.contains("secret path"),
            "The failure was not classified by exit status: ${error.reason}",
        )
        assertFalse(
            error.reason.contains("run-as-vault: /run/torvian"),
            "Helper stderr reached the failure reason: ${error.reason}",
        )
    }

    /**
     * Verifies that a helper which exits before reading stdin still fails as a typed error.
     *
     * The helper validates its root, path and account before it reads, so it can be gone while the value
     * is still being written. That broken pipe has to be classified like any other helper failure: an
     * `IOException` escaping here would bypass the tool's error mapping and answer the model with a raw
     * I/O message.
     */
    @Test
    fun `a helper that exits before reading stdin fails with a typed error`() {
        val stub = recordingStubOrSkip(exitCode = 13, readsStdin = false)
        val secretDir = temporaryDirectory("secret-root").toString()
        // Larger than any pipe buffer, so the write cannot finish before the helper is gone.
        val value = SecretValue(CharArray(1 shl 20) { 'a' })

        val error = value.use { secret ->
            assertIs<SecretMaterialError.PreparationFailed>(
                HelperSecretTreeFactory(secretDir, stub.script.toString(), logger)
                    .create("credentials.json", secret, deadline())
                    .swap().getOrNull()
            )
        }

        assertTrue(
            error.reason.contains("secret path"),
            "The failure was not classified by exit status: ${error.reason}",
        )
    }

    /**
     * Verifies that an exhausted deadline fails the call instead of waiting for a wedged helper.
     */
    @Test
    fun `a helper that outlives the deadline fails the call`() {
        val stub = recordingStubOrSkip(blockSeconds = 30)
        val secretDir = temporaryDirectory("secret-root").toString()
        val factory = HelperSecretTreeFactory(secretDir, stub.script.toString(), logger)

        val startedAt = System.nanoTime()
        val error = assertIs<SecretMaterialError.PreparationFailed>(
            factory.clear("$secretDir/token", System.nanoTime() - TimeUnit.SECONDS.toNanos(1))
                .swap().getOrNull()
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(error.reason.contains("deadline"), "Unexpected reason: ${error.reason}")
        assertTrue(elapsedMillis < 5_000, "The call ignored its deadline: ${elapsedMillis}ms")
    }

    /**
     * Verifies the helper's own identity and mode, and that the isolated account cannot execute it.
     *
     * Requires the installed image: the setuid helper, the `vault` and `agent` accounts and the gate
     * variable. This is the check that a rebuilt helper is still installed as `root:torvian 4750`.
     */
    @Test
    fun `the helper runs as the credential account and only the worker group may execute it`() {
        assumeIsolationGate()

        val vaultIdentity = runHelper(listOf(VAULT_HELPER_PATH.toString(), "id", "-un"))
        assertEquals(0, vaultIdentity.exitCode, "run-as-vault could not exec: ${vaultIdentity.stderr}")
        assertEquals("vault", vaultIdentity.stdout.trim())

        val attributes = Files.readAttributes(VAULT_HELPER_PATH, PosixFileAttributes::class.java)
        assertEquals("root", attributes.owner().name, "The helper must be root-owned")
        assertEquals("torvian", attributes.group().name, "The helper must belong to the worker group")
        // 4750: setuid plus owner rwx, group r-x, nothing for others. Java's POSIX permission set has no
        // setuid bit, so the mode is read through the unix attribute view.
        val mode = Files.getAttribute(VAULT_HELPER_PATH, "unix:mode") as Int
        assertEquals(Integer.parseInt("4750", 8), mode and Integer.parseInt("7777", 8))

        // The isolated account reaches the helper only through run-as-agent, which cannot exec it: the
        // helper is group-executable for 'torvian' alone.
        val asAgent = runHelper(listOf(AGENT_HELPER_PATH.toString(), VAULT_HELPER_PATH.toString(), "id", "-un"))
        assertEquals(
            9,
            asAgent.exitCode,
            "An 'agent' account could execute run-as-vault: ${asAgent.stdout}${asAgent.stderr}",
        )
    }

    /**
     * Verifies that a written secret is `0400`, owned by the credential account, and unreachable
     * outside the root.
     */
    @Test
    fun `write-secret refuses a path outside the root and creates an owner-only file`() {
        assumeIsolationGate()
        val secretDir = temporaryDirectory("secret-root")
        val token = "0123456789abcdef0123456789abcdef"
        val inside = secretDir.resolve(token).resolve("credentials.json")

        val outside = runHelper(listOf(WRITE_SECRET_MODE, secretDir.toString(), "/etc/torvian-secret"))
        assertEquals(13, outside.exitCode, "A path outside the root was not refused: ${outside.stderr}")

        val written = runHelper(
            listOf(WRITE_SECRET_MODE, secretDir.toString(), inside.toString()),
            stdin = "hunter2",
        )
        assertEquals(0, written.exitCode, "The helper failed to write the secret: ${written.stderr}")
        assertEquals("hunter2", Files.readString(inside))
        assertEquals(Integer.parseInt("400", 8), Files.getAttribute(inside, "unix:mode") as Int and Integer.parseInt("777", 8))
        val fileOwner = Files.readAttributes(inside, PosixFileAttributes::class.java).owner().name
        assertEquals("vault", fileOwner, "The secret file must be owned by the credential account")
        val directoryMode =
            (Files.getAttribute(inside.parent, "unix:mode") as Int) and Integer.parseInt("777", 8)
        assertEquals(Integer.parseInt("700", 8), directoryMode, "The per-attach directory must be 0700")
    }

    /**
     * Verifies that removing the same tree twice succeeds, because cleanup runs on unknown state.
     */
    @Test
    fun `clear-secret-dir is idempotent`() {
        assumeIsolationGate()
        val secretDir = temporaryDirectory("secret-root")
        val token = "fedcba9876543210fedcba9876543210"
        val tree = secretDir.resolve(token)

        assertEquals(
            0,
            runHelper(
                listOf(WRITE_SECRET_MODE, secretDir.toString(), tree.resolve("credentials.json").toString()),
                stdin = "hunter2",
            ).exitCode
        )

        val first = runHelper(listOf(CLEAR_SECRET_DIR_MODE, secretDir.toString(), tree.toString()))
        val second = runHelper(listOf(CLEAR_SECRET_DIR_MODE, secretDir.toString(), tree.toString()))

        assertEquals(0, first.exitCode, "The first removal failed: ${first.stderr}")
        assertEquals(0, second.exitCode, "Removing a missing tree is not idempotent: ${second.stderr}")
        assertFalse(Files.exists(tree), "The secret tree survived removal")
    }

    /**
     * @param name Prefix of the directory name.
     * @return A temporary directory this test removes afterwards.
     */
    private fun temporaryDirectory(name: String): Path = createTempDirectory(name).also { directories.add(it) }

    /**
     * @return A monotonic deadline a helper call cannot exceed by accident.
     */
    private fun deadline(): Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)

    /**
     * Runs one helper invocation and captures its output.
     *
     * @param arguments Executable followed by its arguments.
     * @param stdin Text written to the helper's stdin, or null to close it immediately.
     * @return Exit status and captured streams.
     */
    private fun runHelper(arguments: List<String>, stdin: String? = null): HelperRun {
        val process = ProcessBuilder(arguments).redirectErrorStream(false).start()
        process.outputStream.use { stream ->
            if (stdin != null) stream.write(stdin.toByteArray(Charsets.UTF_8))
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The helper did not exit within 30 seconds")
        return HelperRun(
            exitCode = process.exitValue(),
            stdout = process.inputStream.readBytes().decodeToString(),
            stderr = process.errorStream.readBytes().decodeToString(),
        )
    }

    /**
     * Skips the test unless this host can run the real helper.
     */
    private fun assumeIsolationGate() {
        assumeTrue(!isWindows, "the credential helper is a POSIX setuid binary")
        assumeTrue(
            System.getenv(ISOLATION_GATE_VARIABLE) == "true",
            "$ISOLATION_GATE_VARIABLE is not set",
        )
        assumeTrue(Files.isExecutable(VAULT_HELPER_PATH), "the credential helper is unavailable")
        assumeTrue(Files.isExecutable(AGENT_HELPER_PATH), "the command helper is unavailable")
    }

    /**
     * @param exitCode Status the stub exits with.
     * @param stderrLine Optional diagnostic line the stub writes to stderr.
     * @param blockSeconds Optional number of seconds the stub blocks instead of finishing.
     * @param readsStdin Whether the stub consumes its stdin before it exits.
     * @return A recording stub, or skip the test on a host that cannot run a shell script.
     */
    private fun recordingStubOrSkip(
        exitCode: Int = 0,
        stderrLine: String? = null,
        blockSeconds: Int? = null,
        readsStdin: Boolean = true,
    ): RecordingStub {
        assumeTrue(!isWindows, "a stub helper is a POSIX shell script")
        val directory = temporaryDirectory("helper-stub")
        val script = directory.resolve("fake-run-as-vault")
        val arguments = directory.resolve("arguments")
        val stdin = directory.resolve("stdin")
        val body = buildString {
            appendLine("#!/bin/sh")
            // The stub records the argv it was given, then behaves as the failing or blocking helper.
            appendLine("printf '%s\\n' \"\$@\" > '$arguments'")
            // `exec` replaces the stub, so a killed helper takes its blocker with it.
            if (blockSeconds != null) appendLine("exec sleep $blockSeconds")
            if (readsStdin) appendLine("cat > '$stdin'")
            stderrLine?.let { appendLine("echo '$it' >&2") }
            appendLine("exit $exitCode")
        }
        Files.writeString(script, body)
        runCatching {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        }.onFailure { assumeTrue(false, "the host rejected an executable stub: ${it.message}") }
        assumeTrue(script.toFile().canExecute(), "the stub is not executable")
        return RecordingStub(script = script, argumentLog = arguments, stdin = stdin)
    }

    /**
     * Stub helper that records how it was invoked.
     *
     * @property script Executable the factory runs instead of the helper.
     * @property argumentLog File the stub writes its argv to, one argument per line.
     * @property stdin File the stub copies its stdin into.
     */
    private class RecordingStub(val script: Path, private val argumentLog: Path, val stdin: Path) {
        /** @return The argv the stub received, in order. */
        fun recordedArguments(): List<String> = Files.readAllLines(argumentLog)
    }

    /**
     * Captured result of one helper invocation.
     *
     * @property exitCode Status the helper exited with.
     * @property stdout What the helper wrote to its standard output.
     * @property stderr What the helper wrote to its standard error.
     */
    private class HelperRun(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        /** Path the installed credential helper is expected at. */
        val VAULT_HELPER_PATH: Path = Path.of("/usr/local/bin/run-as-vault")

        /** Path the installed command helper is expected at. */
        val AGENT_HELPER_PATH: Path = Path.of("/usr/local/bin/run-as-agent")

        /** Gate variable the deployment sets to enable the checks that need the installed image. */
        const val ISOLATION_GATE_VARIABLE = "CHATBOT_TERMINAL_ISOLATION_GATE"

        /** @see HelperSecretTreeProtocol.WRITE_SECRET_MODE */
        const val WRITE_SECRET_MODE = HelperSecretTreeProtocol.WRITE_SECRET_MODE

        /** @see HelperSecretTreeProtocol.CLEAR_SECRET_DIR_MODE */
        const val CLEAR_SECRET_DIR_MODE = HelperSecretTreeProtocol.CLEAR_SECRET_DIR_MODE

        /** Whether this host has no POSIX shell and no setuid helpers. */
        val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
