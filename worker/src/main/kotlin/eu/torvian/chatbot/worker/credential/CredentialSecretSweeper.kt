package eu.torvian.chatbot.worker.credential

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.Logger

/**
 * Removes secret trees left behind by a previous worker process.
 *
 * The trees are owned by the credential account, so no single account can delete one: the helper empties
 * the tree and the worker removes the emptied directory. A failure is a WARN and never a startup error:
 * leftover material is untidy, not a reason to refuse to run.
 *
 * The sweep runs only when a credential account was resolved, because on hosts without the mechanism
 * there are no such trees and the helper does not exist.
 *
 * @property secretDir Absolute secret root to sweep.
 * @property trees Factory that removes each entry through the helper.
 */
internal class CredentialSecretSweeper(
    private val secretDir: String,
    private val trees: SecretTreeFactory,
) {
    /**
     * Removes every entry currently under the secret root.
     *
     * @param logger Logger receiving a WARN for an entry that could not be removed.
     */
    fun sweep(logger: Logger) {
        val root = Path.of(secretDir)
        if (!Files.isDirectory(root)) return

        val entries = try {
            Files.list(root).use { stream -> stream.toList() }
        } catch (e: IOException) {
            logger.warn(
                "Credential secret directory {} could not be listed; stale trees are left in place: {}",
                secretDir,
                e.message
            )
            return
        }

        var removed = 0
        entries.forEach { entry ->
            // Each removal gets its own budget: the sweep is a startup pass, not one bounded action.
            val deadline = System.nanoTime() + SWEEP_ENTRY_BUDGET_NANOS
            trees.clear(entry.toString(), deadline).fold(
                { error ->
                    logger.warn(
                        "Stale credential tree {} could not be removed: {}",
                        entry.fileName,
                        (error as? SecretMaterialError.PreparationFailed)?.reason ?: error
                    )
                },
                { removed++ }
            )
        }

        if (removed > 0) {
            logger.info("Removed {} stale credential secret tree(s) under {}", removed, secretDir)
        }
    }

    private companion object {
        /** Budget for removing one stale tree, long enough for a helper call on a loaded host. */
        val SWEEP_ENTRY_BUDGET_NANOS: Long = TimeUnit.SECONDS.toNanos(10)
    }
}
