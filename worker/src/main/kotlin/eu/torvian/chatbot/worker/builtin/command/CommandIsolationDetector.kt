package eu.torvian.chatbot.worker.builtin.command

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import eu.torvian.chatbot.worker.config.CommandIsolationConfig
import eu.torvian.chatbot.worker.config.WorkerConfigError
import java.io.File
import java.util.concurrent.TimeUnit
import org.apache.logging.log4j.Logger

/**
 * Outcome of asking the helper which account it runs commands as.
 */
internal sealed interface HelperProbeResult {
    /**
     * The helper ran the probe command and reported the account it became.
     *
     * @property user Account name reported by the helper.
     * @property uid Numeric user id of that account.
     * @property home Home directory of that account in the account database.
     */
    data class EffectiveUser(val user: String, val uid: Long, val home: String) : HelperProbeResult

    /**
     * The helper could not run or could not drop, so isolation is unusable.
     *
     * @property reason Explanation of what was observed.
     */
    data class Failed(val reason: String) : HelperProbeResult
}

/**
 * Decides once, at startup, how built-in tools will spawn their child processes.
 *
 * The mechanism is Linux-only: a root-owned setuid helper drops to an unprivileged account and
 * `exec`s the requested command. Detection is a startup decision rather than a per-command one,
 * because the helper is immutable for the lifetime of the process and a restart is required to
 * change it.
 *
 * Internal because its probe result is an implementation detail of the worker process: callers
 * consume the returned [CommandIsolationResolution] rather than the probe outcome.
 */
internal object CommandIsolationDetector {
    /**
     * Resolves the isolation state for this process.
     *
     * Where the mechanism is unsupported the setting is ignored, so one configuration file stays
     * usable across platforms. Where it is supported and enabled, an unusable helper is a startup
     * failure instead of a silent fallback to the worker's own privileges.
     *
     * @param config Resolved isolation configuration.
     * @param logger Logger used to report the selected state and the reason for any failure.
     * @param probe Helper probe, overridable in tests.
     * @param isLinux Whether the host can support the Linux-only mechanism, overridable in tests.
     * @param ownUserId Provider of this process's numeric user id, invoked only once a helper has
     *   reported an account and overridable in tests.
     * @return Either the reason isolation cannot be provided or the resolved state.
     */
    fun resolve(
        config: CommandIsolationConfig,
        logger: Logger,
        probe: (CommandIsolationConfig) -> HelperProbeResult = CommandIsolationDetector::probeHelper,
        isLinux: Boolean = System.getProperty("os.name").lowercase().contains("linux"),
        ownUserId: () -> Long? = CommandIsolationDetector::currentUserId,
    ): Either<WorkerConfigError, CommandIsolationResolution> = either {
        if (!isLinux) {
            logger.info(
                "Command isolation does not apply on this platform; built-in tool commands run as " +
                    "the worker account with the worker's environment."
            )
            return@either CommandIsolationResolution.none()
        }

        if (!config.enabled) {
            logger.warn(
                "Command isolation is disabled (worker.commandIsolation.enabled = false); built-in " +
                    "tool commands run as the worker account and can read its secrets. This is only " +
                    "safe when the deployment provides isolation by other means."
            )
            return@either CommandIsolationResolution.none()
        }

        when (val result = probe(config)) {
            is HelperProbeResult.EffectiveUser -> {
                // The reported name is checked against the configured one so that a helper pointed at
                // the wrong account is visible as a configuration mistake rather than as a boundary.
                ensure(result.user == config.user) {
                    WorkerConfigError.CommandIsolationUnavailable(
                        "Command isolation helper ${config.helperPath} runs commands as '${result.user}' " +
                            "but worker.commandIsolation.user is '${config.user}'; " +
                            OPT_OUT_HINT
                    )
                }

                // The boundary is numeric: two names can share one user id, and the helper then
                // reports whichever name owns that id, so a name comparison alone would accept a
                // child that runs with the worker's own credentials.
                val workerUserId = ownUserId()
                ensureNotNull(workerUserId) {
                    WorkerConfigError.CommandIsolationUnavailable(
                        "Command isolation helper ${config.helperPath} cannot be checked: this " +
                                "process's own user id could not be read; $OPT_OUT_HINT"
                    )
                }
                ensure(result.uid != workerUserId) {
                    WorkerConfigError.CommandIsolationUnavailable(
                        "Command isolation helper ${config.helperPath} runs commands with user id " +
                            "${result.uid}, which is the worker's own user id, so commands would not " +
                            "be isolated; worker.commandIsolation.user ('${config.user}') must name an " +
                            "account with its own user id; " + OPT_OUT_HINT
                    )
                }

                logger.info(
                    "Command isolation active: built-in tool commands run as '{}' (home {}); only the " +
                        "configured environment variables are passed to them.",
                    result.user,
                    result.home
                )
                CommandIsolationResolution.isolated(
                    helperPath = config.helperPath,
                    user = config.user,
                    home = result.home,
                    logger = logger,
                )
            }

            is HelperProbeResult.Failed -> raise(
                WorkerConfigError.CommandIsolationUnavailable("${result.reason}; $OPT_OUT_HINT")
            )
        }
    }

    /**
     * Runs the helper once and reports the account it actually became.
     *
     * The probe command is `id` itself, whose default output names the account and its numeric user
     * id together, so the identity the helper reports is read from the helper's own credentials
     * rather than derived from an account name.
     *
     * @param config Resolved isolation configuration naming the helper to run.
     * @param timeoutSeconds Upper bound on how long the helper may take to answer.
     * @return The reported account, or a failure describing why the helper could not be used.
     */
    internal fun probeHelper(
        config: CommandIsolationConfig,
        timeoutSeconds: Long = PROBE_TIMEOUT_SECONDS,
    ): HelperProbeResult {
        val helper = File(config.helperPath)
        if (!helper.isFile) {
            return HelperProbeResult.Failed("Command isolation helper ${config.helperPath} does not exist")
        }
        if (!helper.canExecute()) {
            return HelperProbeResult.Failed("Command isolation helper ${config.helperPath} is not executable")
        }

        val process = try {
            // The manager's environment is inherited on purpose: PATH resolution is irrelevant for an
            // absolute helper path, and the probe must observe exactly what the helper does.
            ProcessBuilder(listOf(config.helperPath, PROBE_EXECUTABLE))
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            return HelperProbeResult.Failed(
                "Command isolation helper ${config.helperPath} could not be started: ${e.message}"
            )
        }

        return try {
            // The probe is non-interactive; EOF must be observable by the helper immediately.
            process.outputStream.close()

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // The helper may already have dropped to the isolated account, so this signal can be
                // refused for the same reason the worker cannot signal its own isolated children.
                // ProcessHandle reports whether the signal was accepted, which a refused SIGKILL
                // makes observable instead of leaving it to be assumed either way.
                val signalled = process.toHandle().destroyForcibly()
                // A short wait keeps a reaped probe from lingering as a zombie and lets a refused
                // signal be reported rather than assumed away.
                runCatching { process.waitFor(PROBE_REAP_WAIT_NANOS, TimeUnit.NANOSECONDS) }
                // The probe has established nothing about why it hung, so the reason stays
                // undifferentiated; a cause is claimed only where it was actually observed.
                val signalledClause = if (signalled) "" else " and could not be signalled"
                return HelperProbeResult.Failed(
                    "Command isolation helper ${config.helperPath} did not respond within " +
                        "$timeoutSeconds seconds$signalledClause"
                )
            }

            val stdout = process.inputStream.readBytes().decodeToString()
            val stderr = process.errorStream.readBytes().decodeToString()
            val exitCode = process.exitValue()

            if (exitCode != 0) {
                val stderrLine = stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                val detail = stderrLine?.let { ": $it" }.orEmpty()
                return HelperProbeResult.Failed(
                    "Command isolation helper ${config.helperPath} exited with code $exitCode$detail"
                )
            }

            val reportedLine = stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            if (reportedLine.isNullOrEmpty()) {
                return HelperProbeResult.Failed(
                    "Command isolation helper ${config.helperPath} reported no account name"
                )
            }

            val match = ACCOUNT_FIELD_PATTERN.find(reportedLine) ?: return HelperProbeResult.Failed(
                "Command isolation helper ${config.helperPath} reported '$reportedLine' instead of " +
                        "the 'uid=<user id>(<account>)' line of id"
            )
            val reportedUid = match.groupValues[UID_GROUP_INDEX].toLongOrNull() ?: return HelperProbeResult.Failed(
                "Command isolation helper ${config.helperPath} reported a user id that could not " +
                        "be read: '$reportedLine'"
            )
            // An account without a database record is printed without a name, exactly as `id -un`
            // would print its user id; the user id then stands in as the reported account.
            val reportedUser = match.groupValues[ACCOUNT_GROUP_INDEX]
                .ifEmpty { match.groupValues[UID_GROUP_INDEX] }
            HelperProbeResult.EffectiveUser(reportedUser, reportedUid, resolveHome(reportedUser))
        } catch (e: Exception) {
            return HelperProbeResult.Failed(
                "Command isolation helper ${config.helperPath} could not be probed: ${e.message}"
            )
        } finally {
            process.destroy()
        }
    }

    /**
     * Reads the numeric user id of this process.
     *
     * The comparison against the helper's report needs this process's own identity rather than an
     * account name, because a name says nothing about which user id it resolves to. The utility runs
     * in the worker account, so the id it prints is the one the helper must not become.
     *
     * @return The effective user id, or `null` when the utility could not report it.
     */
    private fun currentUserId(): Long? = runCatching {
        val process = ProcessBuilder(PROBE_EXECUTABLE, USER_ID_FLAG)
            .redirectErrorStream(true)
            .start()
        try {
            process.outputStream.close()
            if (!process.waitFor(ACCOUNT_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.toHandle().destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.readBytes().decodeToString().trim().toLongOrNull()
        } finally {
            process.destroy()
        }
    }.getOrNull()

    /**
     * Resolves the home directory of [user] from the account database.
     *
     * The lookup runs in the worker account and only reads the account database, so it needs no
     * privilege drop. When the database cannot answer, the worker image's conventional layout is
     * assumed so that a missing lookup utility never fails startup by itself.
     *
     * @param user Account name reported by the helper.
     * @return The account's home directory, or `/home/<user>` when it could not be read.
     */
    private fun resolveHome(user: String): String {
        val fromDatabase = runCatching {
            val process = ProcessBuilder(GETENT_EXECUTABLE, GETENT_PASSWD_DATABASE, user)
                .redirectErrorStream(true)
                .start()
            try {
                // The lookup is non-interactive; EOF must be observable by the utility immediately.
                process.outputStream.close()
                if (!process.waitFor(ACCOUNT_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.toHandle().destroyForcibly()
                    return@runCatching null
                }
                process.inputStream.readBytes().decodeToString()
                    .lineSequence()
                    .firstOrNull { it.startsWith("$user:") }
                    ?.split(':')
                    ?.getOrNull(PASSWD_HOME_FIELD_INDEX)
                    ?.takeIf { it.isNotBlank() }
            } finally {
                process.destroy()
            }
        }.getOrNull()

        return fromDatabase ?: (DEFAULT_HOME_PREFIX + user)
    }

    /** Suggestion appended to every failure reason so an operator can act on it. */
    private const val OPT_OUT_HINT =
        "set worker.commandIsolation.enabled = false to run commands as the worker account instead"

    /** POSIX utility that reports a process's account and user id. */
    private const val PROBE_EXECUTABLE = "/usr/bin/id"

    /** `id` flag requesting the numeric user id instead of the account's activity summary. */
    private const val USER_ID_FLAG = "-u"

    /**
     * First field of `id` output: the numeric user id and, when the account database knows it, the
     * account name in parentheses.
     */
    private val ACCOUNT_FIELD_PATTERN = Regex("""uid=(\d+)(?:\(([^)]*)\))?""")

    /** Capture group of [ACCOUNT_FIELD_PATTERN] holding the numeric user id. */
    private const val UID_GROUP_INDEX = 1

    /** Capture group of [ACCOUNT_FIELD_PATTERN] holding the account name. */
    private const val ACCOUNT_GROUP_INDEX = 2

    /** Utility that reads the account database, including entries outside `/etc/passwd`. */
    private const val GETENT_EXECUTABLE = "/usr/bin/getent"

    /** `getent` database holding the account records. */
    private const val GETENT_PASSWD_DATABASE = "passwd"

    /** Colon-separated field of a passwd record that holds the account's home directory. */
    private const val PASSWD_HOME_FIELD_INDEX = 5

    /** Home directory layout the worker image creates for every account it adds. */
    private const val DEFAULT_HOME_PREFIX = "/home/"

    /** Upper bound on how long the helper probe may take before it is considered broken. */
    private const val PROBE_TIMEOUT_SECONDS = 5L

    /** Upper bound on how long the account-database lookup may take before it is abandoned. */
    private const val ACCOUNT_LOOKUP_TIMEOUT_SECONDS = 5L

    /** How long the probe waits for a signalled helper to be reaped before reporting the timeout. */
    private const val PROBE_REAP_WAIT_NANOS = 200_000_000L
}
