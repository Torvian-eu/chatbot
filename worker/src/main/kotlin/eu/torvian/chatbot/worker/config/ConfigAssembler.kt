package eu.torvian.chatbot.worker.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.net.URI
import java.nio.file.Path

/**
 * Strict assembly and validation helpers for worker configuration.
 *
 * Similar to server config assembly, this converts the merged nullable DTO model
 * into a strict domain configuration used by runtime services.
 */

/**
 * Converts the worker app config DTO into a validated domain model.
 *
 * Performs strict validation of all required configuration fields and constraints:
 * - Ensures the worker config group exists
 * - Validates all required string fields are non-blank
 * - Validates the server base URL is an absolute HTTP(S) URL
 * - Ensures the refresh skew is non-negative
 * - Ensures the workspace path is non-blank
 * - Ensures the default command timeout is positive
 *
 * @receiver The [AppConfigDto] to convert and validate.
 * @return Either a logical configuration error or the fully validated [Configuration].
 */
fun AppConfigDto.toDomain(configDir: Path): Either<WorkerConfigError, Configuration> = either {
    val worker = worker ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker"))

    val serverDto = worker.server ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.server"))
    val identityDto = worker.identity ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.identity"))
    val storageDto = worker.storage ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.storage"))
    val authDto = worker.auth ?: AuthConfigDto()
    val workspaceDto = worker.workspace ?: raise(WorkerConfigError.ConfigInvalid("Missing required config group: worker.workspace"))
    val builtInToolsDto = worker.builtInTools ?: BuiltInToolsConfigDto()

    val baseUrl = required("worker.server.baseUrl", serverDto.baseUrl)
    val uid = required("worker.identity.uid", identityDto.uid)
    val displayName = identityDto.displayName?.takeIf { it.isNotBlank() } ?: "my-worker"
    val certificateFingerprint = required("worker.identity.certificateFingerprint", identityDto.certificateFingerprint)
    val certificatePem = required("worker.identity.certificatePem", identityDto.certificatePem)
    val secretsJsonPath = required("worker.storage.secretsJsonPath", storageDto.secretsJsonPath)
    val tokenFilePath = required("worker.storage.tokenFilePath", storageDto.tokenFilePath)
    val refreshSkewSeconds = authDto.refreshSkewSeconds ?: 60L
    val workspacePath = required("worker.workspace.path", workspaceDto.path)
    val builtInToolsEnabled = builtInToolsDto.enabled ?: emptyList()
    val defaultCommandTimeoutSeconds = builtInToolsDto.defaultCommandTimeoutSeconds ?: 600L
    val defaultSearchTimeoutSeconds = builtInToolsDto.defaultSearchTimeoutSeconds ?: 5L
    val commandIsolation = commandIsolationToDomain(worker.commandIsolation)
    val terminalSessions = terminalSessionsToDomain(worker.terminalSessions)
    val trustedSigners = worker.trustedSigners.orEmpty().mapIndexed { index, signerDto ->
        trustedSignerToDomain(signerDto, index)
    }

    validateServerBaseUrl(baseUrl)
    ensure(uid.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.uid must not be blank")
    }
    ensure(displayName.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.displayName must not be blank")
    }
    ensure(certificateFingerprint.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.certificateFingerprint must not be blank")
    }
    ensure(certificatePem.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.identity.certificatePem must not be blank")
    }
    ensure(secretsJsonPath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.storage.secretsJsonPath must not be blank")
    }
    ensure(tokenFilePath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.storage.tokenFilePath must not be blank")
    }
    ensure(refreshSkewSeconds >= 0) {
        WorkerConfigError.ConfigInvalid("worker.auth.refreshSkewSeconds must be >= 0")
    }
    ensure(workspacePath.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.workspace.path must not be blank")
    }
    ensure(defaultCommandTimeoutSeconds > 0) {
        WorkerConfigError.ConfigInvalid("worker.builtInTools.defaultCommandTimeoutSeconds must be > 0")
    }

    // Resolve workspace path relative to config directory using PathResolver
    val pathResolver = PathResolver()
    val resolvedWorkspacePath = pathResolver.resolvePath(
        configDir = configDir,
        configuredPath = workspacePath
    )

    Configuration(
        setupRequired = setup?.required ?: true,
        worker = RuntimeConfig(
            server = ServerConfig(
                baseUrl = baseUrl
            ),
            identity = IdentityConfig(
                uid = uid,
                displayName = displayName,
                certificateFingerprint = certificateFingerprint,
                certificatePem = certificatePem
            ),
            storage = StorageConfig(
                secretsJsonPath = secretsJsonPath,
                tokenFilePath = tokenFilePath
            ),
            auth = AuthConfig(
                refreshSkewSeconds = refreshSkewSeconds
            ),
            workspace = WorkspaceConfig(
                path = resolvedWorkspacePath.toString()
            ),
            builtInTools = BuiltInToolsConfig(
                enabled = builtInToolsEnabled,
                defaultCommandTimeoutSeconds = defaultCommandTimeoutSeconds,
                defaultSearchTimeoutSeconds = defaultSearchTimeoutSeconds
            ),
            commandIsolation = commandIsolation,
            terminalSessions = terminalSessions,
            trustedSigners = trustedSigners
        )
    )
}

/**
 * Effective `worker.commandIsolation.environment.allowPassthrough` names when the block is absent.
 *
 * Kept deliberately short: isolated children only need to locate programs and render text the way
 * the deployment does. Cache roots, proxy settings and project-specific variables are opt-in, so a
 * deployment decides explicitly which of its process secrets a differently-accounted child may see.
 */
private val DEFAULT_COMMAND_ENVIRONMENT_ALLOW_PASSTHROUGH = listOf("PATH", "LANG", "LC_ALL")

/**
 * Effective `worker.commandIsolation.environment.set` entries when the block is absent.
 *
 * The single entry is git's repository-ownership exemption. The shared workspace is owned by the
 * worker account, so git refuses it in a child that runs as the isolated account, and the exemption
 * is what makes git usable there. It is a value rather than a pass-through because the deployment
 * must be able to exempt the child without granting the same trust to a git the worker itself runs,
 * which would execute a workspace repository's configuration as the worker. A deployment that
 * prefers to place the exemption somewhere else removes this entry.
 */
private val DEFAULT_COMMAND_ENVIRONMENT_SET = mapOf(
    "GIT_CONFIG_COUNT" to "1",
    "GIT_CONFIG_KEY_0" to "safe.directory",
    "GIT_CONFIG_VALUE_0" to "*",
)

/** Allowlist entry that forwards the whole worker environment. */
private const val ENVIRONMENT_ALLOW_PASSTHROUGH_WILDCARD = "*"

/**
 * Effective `worker.commandIsolation.enabled` when the switch is absent.
 *
 * The mechanism needs a root-owned setuid helper and a separate account, which only a prepared host
 * has, so isolation is opt-in: without an explicit `true` the worker runs commands as its own account
 * and says so at startup, instead of refusing to start on every host that was never set up for it.
 */
private const val DEFAULT_COMMAND_ISOLATION_ENABLED = false

/** Effective `worker.terminalSessions.defaultIdleTimeoutSeconds` when absent. */
private const val DEFAULT_TERMINAL_IDLE_TIMEOUT_SECONDS = 1800L

/** Effective `worker.terminalSessions.maxIdleTimeoutSeconds` when absent. */
private const val DEFAULT_TERMINAL_MAX_IDLE_TIMEOUT_SECONDS = 28800L

/** Effective `worker.terminalSessions.maxLifetimeSeconds` when absent. */
private const val DEFAULT_TERMINAL_MAX_LIFETIME_SECONDS = 86400L

/** Hard upper bound for `worker.terminalSessions.maxLifetimeSeconds`. */
private const val MAX_TERMINAL_LIFETIME_SECONDS = 604800L

/** Effective `worker.terminalSessions.maxSessions` when absent. */
private const val DEFAULT_TERMINAL_MAX_SESSIONS = 8

/** Effective `worker.terminalSessions.maxClosedSessions` when absent. */
private const val DEFAULT_TERMINAL_MAX_CLOSED_SESSIONS = 32

/** Effective `worker.terminalSessions.maxOutputBytesPerSession` when absent. */
private const val DEFAULT_TERMINAL_MAX_OUTPUT_BYTES = 262144

/** Effective `worker.terminalSessions.quietMillis` when absent. */
private const val DEFAULT_TERMINAL_QUIET_MILLIS = 300L

/** Effective `worker.terminalSessions.defaultWaitMillis` when absent. */
private const val DEFAULT_TERMINAL_WAIT_MILLIS = 5000L

/** Effective `worker.terminalSessions.maxWaitMillis` when absent. */
private const val DEFAULT_TERMINAL_MAX_WAIT_MILLIS = 30000L

/** Effective `worker.terminalSessions.reaperIntervalSeconds` when absent. */
private const val DEFAULT_TERMINAL_REAPER_INTERVAL_SECONDS = 30L

/** Effective `worker.terminalSessions.closedSessionRetentionSeconds` when absent. */
private const val DEFAULT_TERMINAL_CLOSED_RETENTION_SECONDS = 3600L

/** Effective `worker.terminalSessions.columns` when absent. */
private const val DEFAULT_TERMINAL_COLUMNS = 120

/** Effective `worker.terminalSessions.rows` when absent. */
private const val DEFAULT_TERMINAL_ROWS = 40

/** Effective `worker.terminalSessions.tuiEscapeRatioThreshold` when absent. */
private const val DEFAULT_TERMINAL_TUI_ESCAPE_RATIO = 0.15

/** Smallest accepted `worker.terminalSessions.columns`. */
private const val MIN_TERMINAL_COLUMNS = 20

/** Largest accepted `worker.terminalSessions.columns`. */
private const val MAX_TERMINAL_COLUMNS = 500

/** Smallest accepted `worker.terminalSessions.rows`. */
private const val MIN_TERMINAL_ROWS = 5

/** Largest accepted `worker.terminalSessions.rows`. */
private const val MAX_TERMINAL_ROWS = 200

/**
 * Normalizes and validates the terminal-session configuration group.
 *
 * Validation is shape-only: no PTY probe and no platform check, so a configuration file stays
 * portable. It runs even when no session is ever started, because a rejected typo is more useful
 * than a setting that silently does nothing. Unusable values are raised as
 * `WorkerConfigError.ConfigInvalid`.
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param dto Nullable DTO group to resolve.
 * @return The resolved terminal-session configuration with defaults applied.
 */
private fun Raise<WorkerConfigError>.terminalSessionsToDomain(
    dto: TerminalSessionsConfigDto?
): TerminalSessionsConfig {
    val defaultIdleTimeoutSeconds =
        dto?.defaultIdleTimeoutSeconds ?: DEFAULT_TERMINAL_IDLE_TIMEOUT_SECONDS
    val maxIdleTimeoutSeconds =
        dto?.maxIdleTimeoutSeconds ?: DEFAULT_TERMINAL_MAX_IDLE_TIMEOUT_SECONDS
    val maxLifetimeSeconds = dto?.maxLifetimeSeconds ?: DEFAULT_TERMINAL_MAX_LIFETIME_SECONDS
    val maxSessions = dto?.maxSessions ?: DEFAULT_TERMINAL_MAX_SESSIONS
    val maxClosedSessions = dto?.maxClosedSessions ?: DEFAULT_TERMINAL_MAX_CLOSED_SESSIONS
    val maxOutputBytesPerSession =
        dto?.maxOutputBytesPerSession ?: DEFAULT_TERMINAL_MAX_OUTPUT_BYTES
    val quietMillis = dto?.quietMillis ?: DEFAULT_TERMINAL_QUIET_MILLIS
    val defaultWaitMillis = dto?.defaultWaitMillis ?: DEFAULT_TERMINAL_WAIT_MILLIS
    val maxWaitMillis = dto?.maxWaitMillis ?: DEFAULT_TERMINAL_MAX_WAIT_MILLIS
    val reaperIntervalSeconds =
        dto?.reaperIntervalSeconds ?: DEFAULT_TERMINAL_REAPER_INTERVAL_SECONDS
    val closedSessionRetentionSeconds =
        dto?.closedSessionRetentionSeconds ?: DEFAULT_TERMINAL_CLOSED_RETENTION_SECONDS
    val columns = dto?.columns ?: DEFAULT_TERMINAL_COLUMNS
    val rows = dto?.rows ?: DEFAULT_TERMINAL_ROWS
    val tuiEscapeRatioThreshold =
        dto?.tuiEscapeRatioThreshold ?: DEFAULT_TERMINAL_TUI_ESCAPE_RATIO

    ensure(defaultIdleTimeoutSeconds > 0) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.defaultIdleTimeoutSeconds must be > 0"
        )
    }
    ensure(maxIdleTimeoutSeconds > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.maxIdleTimeoutSeconds must be > 0")
    }
    ensure(maxLifetimeSeconds > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.maxLifetimeSeconds must be > 0")
    }
    ensure(maxLifetimeSeconds <= MAX_TERMINAL_LIFETIME_SECONDS) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.maxLifetimeSeconds must be <= $MAX_TERMINAL_LIFETIME_SECONDS"
        )
    }
    ensure(maxSessions > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.maxSessions must be > 0")
    }
    ensure(maxClosedSessions > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.maxClosedSessions must be > 0")
    }
    ensure(maxOutputBytesPerSession > 0) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.maxOutputBytesPerSession must be > 0"
        )
    }
    ensure(quietMillis > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.quietMillis must be > 0")
    }
    ensure(defaultWaitMillis > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.defaultWaitMillis must be > 0")
    }
    ensure(maxWaitMillis > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.maxWaitMillis must be > 0")
    }
    ensure(reaperIntervalSeconds > 0) {
        WorkerConfigError.ConfigInvalid("worker.terminalSessions.reaperIntervalSeconds must be > 0")
    }
    ensure(closedSessionRetentionSeconds > 0) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.closedSessionRetentionSeconds must be > 0"
        )
    }
    ensure(defaultIdleTimeoutSeconds <= maxIdleTimeoutSeconds) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.defaultIdleTimeoutSeconds must be <= maxIdleTimeoutSeconds"
        )
    }
    ensure(maxIdleTimeoutSeconds <= maxLifetimeSeconds) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.maxIdleTimeoutSeconds must be <= maxLifetimeSeconds"
        )
    }
    ensure(defaultWaitMillis <= maxWaitMillis) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.defaultWaitMillis must be <= maxWaitMillis"
        )
    }
    ensure(quietMillis < defaultWaitMillis) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.quietMillis must be < defaultWaitMillis"
        )
    }
    // The reaper is lazy, so an interval that is not shorter than the default idle timeout lets a
    // session outlive its TTL by more than one sweep; this rejects only that confusing setup.
    ensure(reaperIntervalSeconds < defaultIdleTimeoutSeconds) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.reaperIntervalSeconds must be < defaultIdleTimeoutSeconds"
        )
    }
    ensure(columns in MIN_TERMINAL_COLUMNS..MAX_TERMINAL_COLUMNS) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.columns must be in $MIN_TERMINAL_COLUMNS..$MAX_TERMINAL_COLUMNS"
        )
    }
    ensure(rows in MIN_TERMINAL_ROWS..MAX_TERMINAL_ROWS) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.rows must be in $MIN_TERMINAL_ROWS..$MAX_TERMINAL_ROWS"
        )
    }
    ensure(tuiEscapeRatioThreshold > 0.0 && tuiEscapeRatioThreshold <= 1.0) {
        WorkerConfigError.ConfigInvalid(
            "worker.terminalSessions.tuiEscapeRatioThreshold must be > 0 and <= 1"
        )
    }

    return TerminalSessionsConfig(
        defaultIdleTimeoutSeconds = defaultIdleTimeoutSeconds,
        maxIdleTimeoutSeconds = maxIdleTimeoutSeconds,
        maxLifetimeSeconds = maxLifetimeSeconds,
        maxSessions = maxSessions,
        maxClosedSessions = maxClosedSessions,
        maxOutputBytesPerSession = maxOutputBytesPerSession,
        quietMillis = quietMillis,
        defaultWaitMillis = defaultWaitMillis,
        maxWaitMillis = maxWaitMillis,
        reaperIntervalSeconds = reaperIntervalSeconds,
        closedSessionRetentionSeconds = closedSessionRetentionSeconds,
        columns = columns,
        rows = rows,
        tuiEscapeRatioThreshold = tuiEscapeRatioThreshold,
    )
}

/** Shape of a name the child's libc can parse as a single environment variable. */
private val ENVIRONMENT_VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Normalizes and validates the command-isolation configuration group.
 *
 * Validation is shape-only: no host probing and no account lookups, so a configuration file stays
 * portable across platforms. It also runs while isolation is disabled, because a rejected typo is
 * more useful than a setting that silently does nothing. Unusable values are raised as
 * `WorkerConfigError.ConfigInvalid`.
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param dto Nullable DTO group to resolve.
 * @return The resolved isolation configuration with defaults applied.
 */
private fun Raise<WorkerConfigError>.commandIsolationToDomain(
    dto: CommandIsolationConfigDto?
): CommandIsolationConfig {
    val enabled = dto?.enabled ?: DEFAULT_COMMAND_ISOLATION_ENABLED
    val user = dto?.user ?: "agent"
    val helperPath = dto?.helperPath ?: "/usr/local/bin/run-as-agent"
    val allowPassthrough = dto?.environment?.allowPassthrough ?: DEFAULT_COMMAND_ENVIRONMENT_ALLOW_PASSTHROUGH
    val set = dto?.environment?.set ?: DEFAULT_COMMAND_ENVIRONMENT_SET

    ensure(user.isNotBlank()) {
        WorkerConfigError.ConfigInvalid("worker.commandIsolation.user must not be blank")
    }
    // The helper lives on the Linux filesystem, so its path is validated as a POSIX path:
    // Path.isAbsolute is host-dependent and would reject the shipped default on Windows hosts.
    ensure(helperPath.isNotBlank() && helperPath.startsWith("/")) {
        WorkerConfigError.ConfigInvalid("worker.commandIsolation.helperPath must be an absolute path")
    }
    allowPassthrough.forEachIndexed { index, variableName ->
        ensure(variableName.isNotBlank()) {
            WorkerConfigError.ConfigInvalid(
                "worker.commandIsolation.environment.allowPassthrough[$index] must not be blank"
            )
        }
        // A name libc cannot parse, such as "PATH=/usr/bin", would leave the child with a corrupt
        // variable, so it is rejected here rather than surfacing as a puzzling command failure.
        ensure(variableName == ENVIRONMENT_ALLOW_PASSTHROUGH_WILDCARD || ENVIRONMENT_VARIABLE_NAME.matches(variableName)) {
            WorkerConfigError.ConfigInvalid(
                "worker.commandIsolation.environment.allowPassthrough[$index] must be an " +
                    "environment variable name or \"*\""
            )
        }
    }
    set.forEach { (variableName, value) ->
        ensure(variableName.isNotBlank()) {
            WorkerConfigError.ConfigInvalid(
                "worker.commandIsolation.environment.set must not contain a blank name"
            )
        }
        // A name libc cannot parse would leave the child with a corrupt variable, and a value with a
        // NUL byte cannot be passed to a process at all, so both are rejected here rather than
        // surfacing as a puzzling command failure.
        ensure(ENVIRONMENT_VARIABLE_NAME.matches(variableName)) {
            WorkerConfigError.ConfigInvalid(
                "worker.commandIsolation.environment.set['$variableName'] must be an " +
                    "environment variable name"
            )
        }
        ensure(!value.contains('\u0000')) {
            WorkerConfigError.ConfigInvalid(
                "worker.commandIsolation.environment.set['$variableName'] must not contain a NUL byte"
            )
        }
    }
    ensure(
        allowPassthrough.contains("PATH") || allowPassthrough.contains(ENVIRONMENT_ALLOW_PASSTHROUGH_WILDCARD)
    ) {
        WorkerConfigError.ConfigInvalid(
            "worker.commandIsolation.environment.allowPassthrough must provide PATH (or use \"*\")"
        )
    }

    return CommandIsolationConfig(
        enabled = enabled,
        user = user,
        helperPath = helperPath,
        environment = CommandEnvironmentConfig(allowPassthrough = allowPassthrough, set = set),
    )
}

/**
 * Extracts and validates a required string configuration value.
 *
 * If the value is null or missing, raises a configuration error with the path
 * identifying which field is missing. The caller is responsible for further
 * validation of the extracted value (for example checking if it's non-blank).
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param path The configuration path (for example "worker.server.baseUrl") used in error messages.
 * @param value The configuration value to validate, which may be null.
 * @return The non-null configuration value if present.
 * @throws WorkerConfigError.ConfigInvalid if the value is null via Raise DSL.
 */
private fun Raise<WorkerConfigError>.required(path: String, value: String?): String {
    return value ?: raise(WorkerConfigError.ConfigInvalid("Missing required configuration value: $path"))
}

/**
 * Validates the server base URL configuration value.
 *
 * Performs the following checks:
 * - URL is not blank (after trimming whitespace)
 * - URL can be parsed as a valid URI
 * - URI is absolute with a non-blank host
 * - URI scheme is either "http" or "https"
 *
 * If any validation fails, raises a specific configuration error describing the issue.
 *
 * @receiver The Raise context used to propagate configuration errors.
 * @param rawValue The raw server base URL string from configuration (may contain whitespace).
 * @throws WorkerConfigError.ConfigInvalid if the URL fails any validation via Raise DSL.
 */
private fun Raise<WorkerConfigError>.validateServerBaseUrl(rawValue: String) {
    val value = rawValue.trim()
    ensure(!value.isBlank()) {
        WorkerConfigError.ConfigInvalid("worker.server.baseUrl must not be blank")
    }

    val uri = try {
        URI(value)
    } catch (_: Exception) {
        raise(WorkerConfigError.ConfigInvalid("worker.server.baseUrl must be a valid absolute HTTP(S) URL"))
    }

    ensure(uri.isAbsolute && !uri.host.isNullOrBlank()) {
        WorkerConfigError.ConfigInvalid("worker.server.baseUrl must be an absolute HTTP(S) URL")
    }

    val scheme = uri.scheme?.lowercase()
    ensure(scheme == "http" || scheme == "https") {
        WorkerConfigError.ConfigInvalid("worker.server.baseUrl scheme must be http or https")
    }
}
