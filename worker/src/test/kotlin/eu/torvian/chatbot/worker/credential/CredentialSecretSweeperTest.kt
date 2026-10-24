package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Verifies that the startup sweep removes leftover per-attach trees and ignores what it cannot touch.
 */
class CredentialSecretSweeperTest {

    /** Logger used by the sweeper; the test asserts on the resulting filesystem state. */
    private val logger: Logger = LogManager.getLogger(CredentialSecretSweeperTest::class.java)

    /** Temporary secret root the sweep runs against. */
    private val root: Path = createTempDirectory("credential-sweeper-test")

    /** Removes the temporary root once a test finishes. */
    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
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
}
