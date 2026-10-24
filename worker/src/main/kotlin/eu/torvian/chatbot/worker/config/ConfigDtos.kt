package eu.torvian.chatbot.worker.config

import kotlinx.serialization.Serializable

/**
 * Nullable root DTO used while merging layered worker config JSON.
 *
 * This DTO represents the deserialized JSON from merged configuration layers and allows
 * null values for all fields, supporting the merging process where different layers
 * provide different configuration values.
 *
 * @property setup Setup configuration options (nullable).
 * @property worker Runtime worker configuration (nullable).
 */
@Serializable
data class AppConfigDto(
    val setup: SetupConfigDto? = null,
    val worker: RuntimeConfigDto? = null
)

/**
 * DTO for the setup section in worker configuration.
 *
 * Provides optional configuration for the worker setup flow.
 *
 * @property required Whether the worker setup is required before the worker can start.
 *                     Defaults to null (treated as true by domain logic).
 */
@Serializable
data class SetupConfigDto(
    val required: Boolean? = null
)

/**
 * DTO for worker runtime configuration section.
 *
 * Defines all runtime settings required for the worker to operate, grouped by concern.
 * All fields are nullable to support the layered configuration model during merging.
 *
 * @property server Server connection configuration (nullable).
 * @property identity Worker identity and certificate configuration (nullable).
 * @property storage File path configuration for secrets and token storage (nullable).
 * @property auth Authentication timing and skew configuration (nullable).
 * @property workspace Filesystem workspace configuration for built-in tools (nullable).
 * @property builtInTools Built-in tool enablement/prefix configuration (nullable).
 * @property commandIsolation UID isolation for built-in-tool child processes (nullable).
 * @property terminalSessions Limits and timeouts for interactive terminal sessions (nullable).
 * @property trustedSigners Authorized E2EA signers trusted by this worker (nullable).
 */
@Serializable
data class RuntimeConfigDto(
    val server: ServerConfigDto? = null,
    val identity: IdentityConfigDto? = null,
    val storage: StorageConfigDto? = null,
    val auth: AuthConfigDto? = null,
    val workspace: WorkspaceConfigDto? = null,
    val builtInTools: BuiltInToolsConfigDto? = null,
    val commandIsolation: CommandIsolationConfigDto? = null,
    val terminalSessions: TerminalSessionsConfigDto? = null,
    val trustedSigners: List<TrustedSignerDto>? = null
)

/**
 * DTO for the UID boundary applied to processes spawned by built-in tools.
 *
 * The mechanism is Linux-only and opt-in: the switch is honoured where a privilege drop is
 * possible and ignored where the platform offers no mechanism, so one configuration file stays
 * portable. There is deliberately no launcher-mode or daemon selector: the helper binary is the
 * single supported mechanism, and while the switch is on its absence fails startup rather than
 * being a configuration choice.
 *
 * @property enabled Whether command isolation is required where the platform supports it. Nulls
 *   resolve to `false`, so an installation that lacks the helper and the isolated account starts
 *   and runs commands under the worker's own account. With an explicit `true` the worker resolves
 *   the mechanism at startup and refuses to start when it cannot honour it, instead of silently
 *   running commands with the worker's privileges.
 * @property user OS account that isolated children run as. Detection compares the account the
 *   helper actually becomes against this value, so a misconfigured account is reported rather
 *   than silently accepted.
 * @property helperPath Absolute path of the privilege-dropping helper inside the image.
 * @property environment Environment policy applied to isolated children (nullable). It has no
 *   effect unless a privilege drop is actually active, because it exists only to keep the worker's
 *   pass-through secrets away from a child that runs under a different account.
 */
@Serializable
data class CommandIsolationConfigDto(
    val enabled: Boolean? = null,
    val user: String? = null,
    val helperPath: String? = null,
    val environment: CommandEnvironmentConfigDto? = null,
)

/**
 * DTO for the environment an isolated child process receives.
 *
 * @property allowPassthrough Names of worker environment variables the child inherits. A single
 *   `*` entry forwards the whole environment. Nulls resolve to a minimal default, and the account
 *   identity variables are always derived from the isolated user rather than listed here.
 * @property set Variables the child receives with these values, whether or not the worker defines
 *   them. Null resolves to the default set, which carries the git configuration an isolated child
 *   needs to work in the shared workspace.
 */
@Serializable
data class CommandEnvironmentConfigDto(
    val allowPassthrough: List<String>? = null,
    val set: Map<String, String>? = null,
)

/**
 * DTO for the limits and timeouts of interactive terminal sessions.
 *
 * Every field is optional so a deployment can override one value without restating the rest; null
 * resolves to the documented default during assembly. There is deliberately no enable switch: the
 * capability is controlled by server-side tool enablement.
 *
 * @property defaultIdleTimeoutSeconds Idle lifetime applied when `start` omits one.
 * @property maxIdleTimeoutSeconds Upper bound for a requested idle lifetime.
 * @property maxLifetimeSeconds Absolute lifetime after which a session is closed even if active.
 * @property maxSessions Maximum number of live sessions the worker keeps at once.
 * @property maxClosedSessions Maximum number of closed-session tombstones retained for late reads.
 * @property maxOutputBytesPerSession Sanitized output bytes retained per live or closed session.
 * @property quietMillis Silence window after which output is considered settled.
 * @property defaultWaitMillis Default maximum wait for one tool call when the caller omits it.
 * @property maxWaitMillis Upper bound for a caller-supplied wait.
 * @property reaperIntervalSeconds Interval between idle/lifetime/retention sweeps.
 * @property closedSessionRetentionSeconds How long a closed-session tombstone is retained.
 * @property columns Initial PTY width in character cells.
 * @property rows Initial PTY height in character cells.
 * @property tuiEscapeRatioThreshold Escape-to-printable ratio that marks a session as TUI-driven.
 */
@Serializable
data class TerminalSessionsConfigDto(
    val defaultIdleTimeoutSeconds: Long? = null,
    val maxIdleTimeoutSeconds: Long? = null,
    val maxLifetimeSeconds: Long? = null,
    val maxSessions: Int? = null,
    val maxClosedSessions: Int? = null,
    val maxOutputBytesPerSession: Int? = null,
    val quietMillis: Long? = null,
    val defaultWaitMillis: Long? = null,
    val maxWaitMillis: Long? = null,
    val reaperIntervalSeconds: Long? = null,
    val closedSessionRetentionSeconds: Long? = null,
    val columns: Int? = null,
    val rows: Int? = null,
    val tuiEscapeRatioThreshold: Double? = null,
)

/**
 * DTO for a signer whose E2EA signatures are trusted by the worker.
 *
 * @property signerId Stable identifier emitted by signed envelopes for the signer.
 * @property publicKeyBase64 Base64-encoded public key used to verify signatures from this signer.
 * @property permissions Permission tokens granted to signatures produced by this signer.
 */
@Serializable
data class TrustedSignerDto(
    val signerId: String,
    val publicKeyBase64: String,
    val permissions: List<String>
)

/**
 * DTO for server connection configuration.
 *
 * @property baseUrl The base URL of the chatbot server the worker connects to.
 */
@Serializable
data class ServerConfigDto(
    val baseUrl: String? = null
)

/**
 * DTO for worker identity configuration.
 *
 * @property uid Unique identifier for this worker instance.
 * @property displayName Human-readable label for this worker shown in management UIs.
 * @property workerId Numeric worker id setup records at registration; null disables the per-row
 *   credential worker-id check.
 * @property certificateFingerprint SHA-256 fingerprint of the worker certificate.
 * @property certificatePem PEM-encoded public certificate for the worker identity.
 */
@Serializable
data class IdentityConfigDto(
    val uid: String? = null,
    val displayName: String? = null,
    val workerId: Long? = null,
    val certificateFingerprint: String? = null,
    val certificatePem: String? = null
)

/**
 * DTO for storage path configuration.
 *
 * @property secretsJsonPath File path to the worker's secrets file containing the private key.
 * @property tokenFilePath File path where the worker stores its authentication token.
 */
@Serializable
data class StorageConfigDto(
    val secretsJsonPath: String? = null,
    val tokenFilePath: String? = null
)

/**
 * DTO for authentication timing configuration.
 *
 * @property refreshSkewSeconds Number of seconds before token expiration to refresh it.
 *                               Defaults to 60 seconds if not specified.
 */
@Serializable
data class AuthConfigDto(
    val refreshSkewSeconds: Long? = 60
)

/**
 * DTO for the worker's filesystem workspace used by built-in tools.
 *
 * @property path Path to the workspace directory. May be relative to the worker's config directory
 *   or absolute. Relative paths are resolved during configuration assembly.
 */
@Serializable
data class WorkspaceConfigDto(
    val path: String? = null
)

/**
 * DTO for built-in tool configuration.
 *
 * @property enabled List of unprefixed built-in tool names to enable. Use a single `*` to enable all.
 * @property defaultCommandTimeoutSeconds Default timeout in seconds for the `run_command` tool.
 * @property defaultSearchTimeoutSeconds Default timeout in seconds for the `search_text` tool.
 */
@Serializable
data class BuiltInToolsConfigDto(
    val enabled: List<String>? = null,
    val defaultCommandTimeoutSeconds: Long? = null,
    val defaultSearchTimeoutSeconds: Long? = null
)
