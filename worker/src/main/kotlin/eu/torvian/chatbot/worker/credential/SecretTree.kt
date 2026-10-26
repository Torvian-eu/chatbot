package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.builtin.command.remainingNanos
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.Logger

/**
 * Logical failure while preparing a secret for delivery.
 *
 * The reason names the operation or the file the worker manages, never a value.
 */
internal sealed interface SecretMaterialError {
    /**
     * The secret could not be prepared for the profile's channel.
     *
     * @property reason What failed, in terms of files, permissions or configuration.
     */
    data class PreparationFailed(val reason: String) : SecretMaterialError
}

/**
 * One per-attach secret directory and the file inside it.
 *
 * The credential helper creates the directory and the file, because the worker cannot `chown` a file to
 * the credential account. Removal is split: the helper unlinks the contents of the directory, which
 * that account owns, and the worker deletes the directory itself, which it owns through the secret
 * root.
 */
internal interface SecretTree {
    /** Absolute path of the file written into this tree. */
    val secretPath: String

    /** Absolute path of the per-attach directory. */
    val directoryPath: String
}

/**
 * Creates and removes the per-attach secret trees.
 *
 * A seam rather than a helper call in place: tests need a temporary directory the test account owns,
 * and the production implementation is the only place that may touch the helper.
 *
 * Both directions take the caller's monotonic deadline, because they block on a helper process while
 * the caller holds the session's action lock; a helper with its own private timeout would hold that
 * lock for longer than the action budget allows.
 */
internal interface SecretTreeFactory {
    /**
     * Creates a fresh per-attach directory holding [fileName] with [value] as its contents.
     *
     * @param fileName Single path component of the file to create.
     * @param value Decrypted secret to write.
     * @param deadline Monotonic deadline the whole call must respect.
     * @return Either a typed failure or the created tree.
     */
    fun create(fileName: String, value: SecretValue, deadline: Long): Either<SecretMaterialError, SecretTree>

    /**
     * Removes one per-attach directory, including its contents.
     *
     * The work is two steps, because no single account can do both: the credential account owns the
     * directory and its contents but has traverse-only permission on the secret root, while the worker
     * owns the root and cannot unlink inside the directory. The helper therefore empties the directory
     * and this call deletes the emptied directory. A directory that is not empty once the helper is
     * done is a failure, never a reason to delete recursively.
     *
     * [deadline] bounds the helper call only. Deleting the already emptied directory is a single unlink
     * and is not gated on the remaining budget, so a helper that finished late still leaves nothing
     * behind for the next sweep to report.
     *
     * Missing entries count as success, because cleanup runs on paths whose state is unknown.
     *
     * @param directoryPath Absolute path of the directory to remove.
     * @param deadline Monotonic deadline the whole call must respect.
     * @return Either a typed failure or unit once the directory is gone.
     */
    fun clear(directoryPath: String, deadline: Long): Either<SecretMaterialError, Unit>
}

/**
 * argv contract with the credential helper's secret modes.
 *
 * The helper validates `argc` exactly, so the shape is part of the boundary: every mode is followed by
 * the secret root and the target path, and nothing else.
 */
internal object HelperSecretTreeProtocol {
    /** Helper mode that creates one secret file from stdin. */
    const val WRITE_SECRET_MODE = "--write-secret"

    /** Helper mode that empties one secret tree without removing its directory. */
    const val CLEAR_SECRET_DIR_MODE = "--clear-secret-dir"

    /**
     * @param secretDir Secret root the helper enforces as a boundary.
     * @param secretPath Absolute path of the file to create.
     * @return Arguments passed to the helper after its own path.
     */
    fun writeSecretArguments(secretDir: String, secretPath: String): List<String> =
        listOf(WRITE_SECRET_MODE, secretDir, secretPath)

    /**
     * @param secretDir Secret root the helper enforces as a boundary.
     * @param directoryPath Absolute path of the directory to empty.
     * @return Arguments passed to the helper after its own path.
     */
    fun clearSecretDirArguments(secretDir: String, directoryPath: String): List<String> =
        listOf(CLEAR_SECRET_DIR_MODE, secretDir, directoryPath)

    /**
     * Describes a helper exit status without repeating anything the helper printed.
     *
     * The helper's stderr names absolute paths, so it is logged and never returned: only the status
     * decides what the caller and the model are told.
     *
     * @param exitCode Status the credential helper exited with; each failure it can report has its own.
     * @return A fixed description of that status.
     */
    fun describeExitStatus(exitCode: Int): String = when (exitCode) {
        EXIT_USAGE -> "the helper rejected its arguments"
        EXIT_NO_ACCOUNT -> "the credential account does not exist on this host"
        EXIT_PRCTL -> "the helper could not make the privilege drop irreversible"
        EXIT_EXEC -> "the helper could not run its command"
        EXIT_SETPGID -> "the helper could not create a process group"
        EXIT_SIGNAL -> "the helper could not deliver the signal"
        EXIT_BAD_ARGUMENT -> "the helper rejected its argument"
        EXIT_BAD_PATH -> "the secret path is not a usable path inside the secret root"
        EXIT_IO -> "the helper could not write or remove the secret file"
        else -> "the helper failed"
    }

    /** Exit status for an argv the helper does not accept. */
    private const val EXIT_USAGE = 2

    /** Exit status for a missing credential account. */
    private const val EXIT_NO_ACCOUNT = 3

    /** Exit status for a failed `prctl(PR_SET_NO_NEW_PRIVS)`. */
    private const val EXIT_PRCTL = 8

    /** Exit status for a failed `execvp`. */
    private const val EXIT_EXEC = 9

    /** Exit status for a failed `setpgid`. */
    private const val EXIT_SETPGID = 10

    /** Exit status for a signal that could not be delivered. */
    private const val EXIT_SIGNAL = 11

    /** Exit status for a malformed signal argument. */
    private const val EXIT_BAD_ARGUMENT = 12

    /** Exit status for a path outside the secret root. */
    private const val EXIT_BAD_PATH = 13

    /** Exit status for a failed secret file write or removal. */
    private const val EXIT_IO = 14
}

/**
 * [SecretTreeFactory] that drives the credential helper.
 *
 * Both modes run the helper as a plain child process: it needs no PTY, no process group and no
 * secret on its command line, and it reads the value from a piped stdin. Failure detail is taken from
 * the helper's exit status, and whatever the helper wrote to stderr is logged rather than returned,
 * because it names absolute paths.
 *
 * @property secretDir Absolute secret root the helper enforces as a boundary.
 * @property vaultHelperPath Absolute path of the credential helper.
 * @property logger Receives the helper's stderr at WARN; never receives the value.
 * @property secureRandom Source of the per-attach token.
 */
internal class HelperSecretTreeFactory(
    private val secretDir: String,
    private val vaultHelperPath: String,
    private val logger: Logger,
    private val secureRandom: SecureRandom = SecureRandom(),
) : SecretTreeFactory {

    override fun create(
        fileName: String,
        value: SecretValue,
        deadline: Long,
    ): Either<SecretMaterialError, SecretTree> = either {
        // A fresh directory per attach keeps concurrent profiles apart and makes the tree name
        // uninteresting: the directory itself is mode 0700 and owned by the credential account.
        val token = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes).toHex()
        val directoryPath = "$secretDir/$token"
        val secretPath = "$directoryPath/$fileName"

        val bytes = value.copyBytes()
        try {
            runHelper(HelperSecretTreeProtocol.writeSecretArguments(secretDir, secretPath), bytes, deadline).bind()
        } finally {
            bytes.fill(0)
        }

        HelperSecretTree(directoryPath = directoryPath, secretPath = secretPath)
    }

    override fun clear(
        directoryPath: String,
        deadline: Long,
    ): Either<SecretMaterialError, Unit> = either {
        runHelper(
            arguments = HelperSecretTreeProtocol.clearSecretDirArguments(secretDir, directoryPath),
            stdin = null,
            deadline = deadline,
        ).bind()

        removeEmptiedDirectory(directoryPath).bind()
    }

    /**
     * Deletes the tree directory the helper has just emptied.
     *
     * The helper drops to the credential account, which may only traverse the secret root, so removing
     * an entry inside the root is the worker's job. Nothing is deleted recursively: a directory that
     * still holds something means the helper failed to unlink it, and that must surface as a failure.
     * No deadline applies here: the delete is a single unlink, and skipping it would leave the empty
     * directory the sweep reports after the next start.
     *
     * @param directoryPath Absolute path of the emptied directory.
     * @return Either a typed failure or unit once the directory is gone.
     */
    private fun removeEmptiedDirectory(
        directoryPath: String,
    ): Either<SecretMaterialError, Unit> = either {
        try {
            Files.delete(Path.of(directoryPath))
        } catch (_: NoSuchFileException) {
            // Another cleanup already removed it; asking twice is normal.
        } catch (_: DirectoryNotEmptyException) {
            raise(
                SecretMaterialError.PreparationFailed(
                    "secret tree directory $directoryPath is not empty after the credential helper emptied it"
                )
            )
        } catch (e: IOException) {
            raise(
                SecretMaterialError.PreparationFailed(
                    "secret tree directory $directoryPath could not be removed: ${e.message}"
                )
            )
        }
    }

    /**
     * Runs the helper once and classifies its outcome.
     *
     * @param arguments Helper arguments after the executable path.
     * @param stdin Bytes to pipe to the helper, or `null` to close its stdin immediately.
     * @param deadline Monotonic deadline the whole call must respect.
     * @return Either a typed failure or unit when the helper exited successfully.
     */
    private fun runHelper(
        arguments: List<String>,
        stdin: ByteArray?,
        deadline: Long,
    ): Either<SecretMaterialError, Unit> = either {
        val process = try {
            ProcessBuilder(listOf(vaultHelperPath) + arguments)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            raise(
                SecretMaterialError.PreparationFailed(
                    "credential helper $vaultHelperPath could not be started: ${e.message}"
                )
            )
        }

        try {
            // The helper validates its root, path and account before it reads stdin, so it can exit
            // while the worker is still writing. That broken pipe is a classification problem, not an
            // I/O failure to propagate: the exit status below says what the helper refused.
            var writeFailed = false
            try {
                process.outputStream.use { stream ->
                    if (stdin != null) stream.write(stdin)
                }
            } catch (e: IOException) {
                writeFailed = true
                logger.warn("Credential helper {} stopped reading its input: {}", vaultHelperPath, e.message)
            }

            val budget = remainingNanos(deadline)
            if (budget <= 0L || !process.waitFor(budget, TimeUnit.NANOSECONDS)) {
                process.toHandle().destroyForcibly()
                raise(
                    SecretMaterialError.PreparationFailed(
                        "credential helper $vaultHelperPath did not respond before the action deadline"
                    )
                )
            }

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logHelperDiagnostics(exitCode, process.errorStream.readBytes().decodeToString())
                val description = HelperSecretTreeProtocol.describeExitStatus(exitCode)
                raise(
                    SecretMaterialError.PreparationFailed(
                        "credential helper $vaultHelperPath failed: $description"
                    )
                )
            }
            ensure(!writeFailed) {
                SecretMaterialError.PreparationFailed(
                    "credential helper $vaultHelperPath exited before the whole secret was written"
                )
            }
        } finally {
            process.destroy()
        }
    }

    /**
     * Logs the helper's own diagnostic line, which is the only place it may appear.
     *
     * The line can name the per-attach directory and the helper path, so it stays out of results and
     * out of everything a model can read.
     *
     * @param exitCode Status the helper exited with.
     * @param stderr What the helper wrote to its error stream, capped to its first line.
     */
    private fun logHelperDiagnostics(exitCode: Int, stderr: String) {
        val detail = stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        logger.warn(
            "Credential helper {} exited with code {}: {}",
            vaultHelperPath,
            exitCode,
            detail.ifEmpty { "no diagnostic output" }
        )
    }

    private companion object {
        /** Entropy of the per-attach directory name. */
        const val TOKEN_BYTES = 16
    }
}

/**
 * [SecretTree] returned by [HelperSecretTreeFactory]; it holds paths only, because the helper owns the
 * files themselves.
 *
 * @property directoryPath Absolute path of the per-attach directory.
 * @property secretPath Absolute path of the file inside it.
 */
private data class HelperSecretTree(
    override val directoryPath: String,
    override val secretPath: String,
) : SecretTree

/**
 * @return This byte array as lower-case hexadecimal.
 */
private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
