package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.worker.config.CredentialsConfig
import eu.torvian.chatbot.worker.config.WorkerConfigError
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import org.apache.logging.log4j.Logger

/**
 * Startup checks for the paths credential profile targets depend on.
 *
 * The checks run only when a credential account was resolved: on hosts without the mechanism there are
 * no such paths, and the POSIX calls they need do not exist there. A failure is a startup error rather
 * than a deferred `attach` error, because every condition here is a deployment mistake that no profile
 * can fix.
 *
 * @property credentials Resolved credential deployment configuration.
 * @property modeReader Reads the POSIX mode of a directory; injectable so a test can exercise a mode
 *   that cannot be read.
 */
internal class CredentialEnvironmentValidator(
    private val credentials: CredentialsConfig,
    private val modeReader: (Path) -> Set<PosixFilePermission> = { Files.getPosixFilePermissions(it) },
) {
    /**
     * Verifies the secret root, the profile working directory and the askpass helper.
     *
     * The secret root is created when absent, because an empty tmpfs mount starts without it. The
     * working directory is never created here: creating it as the worker would leave it owned by the
     * worker, which is exactly the condition the check exists to reject.
     *
     * The mode and owner checks cover the two configured directories only. The entries above them are
     * not validated: the credential helper re-resolves and re-checks every path it creates or removes,
     * so a traversed parent is a deployment concern rather than a capability the worker relies on.
     *
     * @param logger Logger used to report the resolved paths without any secret material.
     * @return Either a startup failure or unit when every path is usable.
     */
    fun validate(logger: Logger): Either<WorkerConfigError, Unit> = either {
        val secretRoot = Path.of(credentials.secretDir)
        // Created only when absent: an existing entry that is not a directory must fail the check below
        // rather than be reported as a creation failure.
        val createdSecretRoot = !Files.exists(secretRoot)
        if (createdSecretRoot) {
            try {
                Files.createDirectories(secretRoot)
            } catch (e: IOException) {
                raise(
                    WorkerConfigError.CredentialsUnavailable(
                        "worker.credentials.secretDir ${credentials.secretDir} could not be created: " +
                            (e.message ?: e::class.simpleName.orEmpty())
                    )
                )
            }
        }
        ensure(Files.isDirectory(secretRoot)) {
            WorkerConfigError.CredentialsUnavailable(
                "worker.credentials.secretDir ${credentials.secretDir} is not a directory"
            )
        }
        ensure(Files.isWritable(secretRoot)) {
            WorkerConfigError.CredentialsUnavailable(
                "worker.credentials.secretDir ${credentials.secretDir} is not writable by the worker; " +
                    "the worker must be able to list the trees it sweeps"
            )
        }
        if (supportsPosix(secretRoot)) {
            // A directory the worker creates inherits its umask, which commonly makes it world-readable.
            // Applying the deployment's own mode keeps a fresh volume as tight as the image's tree.
            if (createdSecretRoot) {
                runCatching {
                    Files.setPosixFilePermissions(secretRoot, SECRET_ROOT_PERMISSIONS)
                }
            }
            val permissions = requireMode(
                secretRoot,
                "worker.credentials.secretDir ${credentials.secretDir}",
            )
            // Listing the trees is a read capability, so no read may be granted outside the owner: a group
            // read bit would let every account of that group list the per-attach tree names, which is the
            // disclosure this check exists to prevent. Traversal by others is all the deployment needs.
            ensure(
                PosixFilePermission.OTHERS_READ !in permissions &&
                    PosixFilePermission.OTHERS_WRITE !in permissions &&
                    PosixFilePermission.GROUP_READ !in permissions &&
                    PosixFilePermission.GROUP_WRITE !in permissions
            ) {
                WorkerConfigError.CredentialsUnavailable(
                    "worker.credentials.secretDir ${credentials.secretDir} is readable or writable by an " +
                        "account other than its owner; make it 0711 owned by the worker account so only the " +
                        "worker can list it and every other account may only traverse it"
                )
            }
        }

        val workingDirectory = Path.of(credentials.secretWorkingDirectory)
        ensure(Files.isDirectory(workingDirectory)) {
            WorkerConfigError.CredentialsUnavailable(
                "worker.credentials.secretWorkingDirectory ${credentials.secretWorkingDirectory} does not " +
                    "exist or is not a directory; create it owned by '${credentials.vaultUser}' before " +
                    "starting the worker"
            )
        }
        if (supportsPosix(workingDirectory)) {
            val owner = runCatching { Files.getOwner(workingDirectory).name }.getOrNull()
            ensure(owner == credentials.vaultUser) {
                WorkerConfigError.CredentialsUnavailable(
                    "worker.credentials.secretWorkingDirectory ${credentials.secretWorkingDirectory} is " +
                        "owned by '${owner ?: "an unreadable account"}' instead of " +
                        "'${credentials.vaultUser}'; a profile that loads configuration from its working " +
                        "directory would otherwise execute another account's files"
                )
            }

            val permissions = requireMode(
                workingDirectory,
                "worker.credentials.secretWorkingDirectory ${credentials.secretWorkingDirectory}",
            )
            // The group of that directory is the shared group, which the account ordinary sessions run as
            // belongs to, so a group-write bit would let a model-driven shell plant files a profile loads.
            ensure(
                PosixFilePermission.GROUP_WRITE !in permissions &&
                    PosixFilePermission.OTHERS_WRITE !in permissions
            ) {
                WorkerConfigError.CredentialsUnavailable(
                    "worker.credentials.secretWorkingDirectory ${credentials.secretWorkingDirectory} is " +
                        "writable by a group or by others; it must be writable by '${credentials.vaultUser}' only"
                )
            }
        }

        // An askpass profile is handed this script as its prompt source, and nothing else on the worker
        // path installs or repairs it, so a deployment that lacks it refuses to start instead of failing
        // the first operator-driven attach.
        val askpassScript = Path.of(credentials.askpassScriptPath)
        ensure(Files.isRegularFile(askpassScript) && Files.isExecutable(askpassScript)) {
            WorkerConfigError.CredentialsUnavailable(
                "worker.credentials.askpassScriptPath ${credentials.askpassScriptPath} does not exist or is " +
                    "not executable; install the askpass helper in the worker image or mount it read-only"
            )
        }

        logger.info(
            "Credential paths ready: secrets under {} (cleared per attach), profile working " +
                "directory {}, askpass helper {}.",
            credentials.secretDir,
            credentials.secretWorkingDirectory,
            credentials.askpassScriptPath
        )
    }

    /**
     * Reads a directory's POSIX mode, treating an unreadable mode as a startup failure.
     *
     * These checks exist to refuse a permissive directory, so an unknown mode must fail rather than
     * pass: an empty permission set would satisfy every forbidden-bit test.
     *
     * @param path Directory whose mode is being checked.
     * @param description Configuration key and value naming [path] in the failure reason.
     * @return The directory's permission bits.
     */
    private fun Raise<WorkerConfigError>.requireMode(
        path: Path,
        description: String,
    ): Set<PosixFilePermission> = runCatching { modeReader(path) }.getOrElse { error ->
        raise(
            WorkerConfigError.CredentialsUnavailable(
                "the mode of $description could not be read, so its permissions cannot be confirmed: " +
                    (error.message ?: error::class.simpleName.orEmpty())
            )
        )
    }

    /**
     * @param path Directory to inspect.
     * @return Whether the filesystem behind [path] exposes POSIX ownership and mode bits.
     */
    private fun supportsPosix(path: Path): Boolean = runCatching {
        Files.getFileStore(path).supportsFileAttributeView(POSIX_VIEW)
    }.getOrDefault(false)

    private companion object {
        /** File-attribute view name that reports ownership and permission bits. */
        const val POSIX_VIEW = "posix"

        /**
         * Mode a freshly created secret root receives: the worker owns it, others may only traverse.
         *
         * Profile targets must reach the file inside their own per-attach directory, which needs the
         * root to be traversable, while nothing but the worker may list the trees.
         */
        val SECRET_ROOT_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx--x--x")
    }
}
