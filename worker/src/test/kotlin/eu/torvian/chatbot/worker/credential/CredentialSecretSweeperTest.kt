package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Verifies that the startup sweep removes leftover per-attach trees and ignores what it cannot touch.
 */
class CredentialSecretSweeperTest {

    /** Logger used by the sweeper; the test asserts on the resulting filesystem state. */
    private val logger: Logger = LogManager.getLogger(CredentialSecretSweeperTest::class.java)

    /** Temporary secret root the sweep runs against. */
    private val root: Path = createTempDirectory("credential-sweeper-test")

    /** Directories holding stub helpers, removed once a test finishes. */
    private val stubDirectories = mutableListOf<Path>()

    /** Removes the temporary root once a test finishes. */
    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
        stubDirectories.forEach { it.toFile().deleteRecursively() }
    }

    /** Verifies that every existing tree is removed through the factory. */
    @Test
    fun `removes every stale tree`() {
        val first = root.resolve("0123").createDirectory()
        Files.writeString(first.resolve("askpass"), "value")
        val second = root.resolve("4567").createDirectory()
        val factory = RecordingTreeFactory()

        CredentialSecretSweeper(root.toString(), factory).sweep(logger)

        assertEquals(listOf(first.toString(), second.toString()).sorted(), factory.cleared.sorted())
        assertTrue(!first.exists() && !second.exists(), "A stale tree survived the sweep")
    }

    /** Verifies that a missing secret root is not an error. */
    @Test
    fun `missing secret root is ignored`() {
        val factory = RecordingTreeFactory()

        CredentialSecretSweeper(root.resolve("absent").toString(), factory).sweep(logger)

        assertTrue(factory.cleared.isEmpty())
    }

    /** Verifies that a failing entry does not stop the sweep or fail startup. */
    @Test
    fun `failing entry does not abort the sweep`() {
        val first = root.resolve("0123").createDirectory()
        val second = root.resolve("4567").createDirectory()
        val factory = RecordingTreeFactory(failing = first.toString())

        CredentialSecretSweeper(root.toString(), factory).sweep(logger)

        assertTrue(second.toString() in factory.cleared, "The sweep stopped at the first failure")
    }

    /**
     * Verifies that the sweep removes a stale tree with the production factory.
     *
     * The helper empties a tree and the worker deletes the emptied directory, so a sweep that only
     * removed files would leave an empty entry behind and warn about it on every start.
     */
    @Test
    fun `removes a stale empty tree with the production factory`() {
        assumeTrue(!isWindows, "a stub helper is a POSIX shell script")
        val stub = stubHelper()
        val tree = root.resolve("0123").createDirectory()

        CredentialSecretSweeper(root.toString(), HelperSecretTreeFactory(root.toString(), stub.toString(), logger))
            .sweep(logger)

        assertTrue(!tree.exists(), "The stale tree survived the sweep")
    }

    /**
     * Verifies that a tree the helper did not empty survives the sweep and is never removed recursively.
     *
     * The stub plays a helper that reports success without unlinking anything, so the worker-side delete
     * finds a non-empty directory. Reporting success there would claim a tree as cleaned while its file
     * was still present, which is why the sweep warns and leaves it in place.
     */
    @Test
    fun `a tree the helper did not empty survives the sweep`() {
        assumeTrue(!isWindows, "a stub helper is a POSIX shell script")
        val stub = stubHelper()
        val tree = root.resolve("0123").createDirectory()
        val leftover = Files.writeString(tree.resolve("askpass"), "value")

        CredentialSecretSweeper(root.toString(), HelperSecretTreeFactory(root.toString(), stub.toString(), logger))
            .sweep(logger)

        assertTrue(tree.exists(), "The sweep removed a tree the helper did not empty")
        assertTrue(Files.exists(leftover), "The sweep removed a file the helper left behind")
    }

    /**
     * Creates a stub credential helper that does nothing and reports success.
     *
     * @return Absolute path of the executable stub.
     */
    private fun stubHelper(): Path {
        val directory = createTempDirectory("credential-sweeper-stub").also { stubDirectories.add(it) }
        val script = directory.resolve("fake-run-as-vault")
        Files.writeString(script, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }

    /**
     * [SecretTreeFactory] that records removals instead of driving the helper.
     *
     * @property failing Path whose removal fails, or `null` when everything succeeds.
     */
    private class RecordingTreeFactory(private val failing: String? = null) : SecretTreeFactory {
        /** Paths the sweeper asked to remove. */
        val cleared = mutableListOf<String>()

        override fun create(
            fileName: String,
            value: SecretValue,
            deadline: Long,
        ): Either<SecretMaterialError, SecretTree> =
            SecretMaterialError.PreparationFailed("the sweep never creates trees").left()

        override fun clear(directoryPath: String, deadline: Long): Either<SecretMaterialError, Unit> {
            if (directoryPath == failing) {
                return SecretMaterialError.PreparationFailed("permission denied").left()
            }
            Path.of(directoryPath).toFile().deleteRecursively()
            cleared.add(directoryPath)
            return Unit.right()
        }
    }

    private companion object {
        /** Whether this host has no POSIX shell and therefore cannot run a stub helper. */
        val isWindows: Boolean = System.getProperty("os.name").contains("windows", ignoreCase = true)
    }
}
