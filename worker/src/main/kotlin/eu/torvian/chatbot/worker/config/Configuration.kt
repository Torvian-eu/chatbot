package eu.torvian.chatbot.worker.config

/**
 * Root worker configuration, analogous to the server's root app configuration model.
 *
 * @property setupRequired Whether worker setup flow is still required.
 * @property worker Runtime worker settings used by the process.
 */
data class Configuration(
    val setupRequired: Boolean,
    val worker: RuntimeConfig
)

/**
 * Grouped runtime configuration for the worker process.
 *
 * @property server Server connection settings.
 * @property identity Worker identity and certificate material.
 * @property storage Secrets and token file paths.
 * @property auth Authentication timing configuration.
 * @property workspace Filesystem workspace for built-in tools.
 * @property builtInTools Built-in tool configuration (enabled names, default command timeout).
 * @property commandIsolation UID boundary applied to built-in-tool child processes.
 * @property credentials Deployment paths for the credential-bearing account.
 * @property terminalSessions Limits and timeouts for interactive terminal sessions.
 * @property trustedSigners Authorized E2EA signers trusted by this worker.
 */
data class RuntimeConfig(
    val server: ServerConfig,
    val identity: IdentityConfig,
    val storage: StorageConfig,
    val auth: AuthConfig,
    val workspace: WorkspaceConfig,
    val builtInTools: BuiltInToolsConfig,
    val commandIsolation: CommandIsolationConfig,
    val credentials: CredentialsConfig,
    val terminalSessions: TerminalSessionsConfig,
    val trustedSigners: List<TrustedSigner>
)

/**
 * Deployment knobs for credential profile targets.
 *
 * Credential profiles and their secret values are not worker configuration: they are managed on the
 * server and delivered over the authenticated worker protocol. Only the paths and limits the image
 * provides live here, and there is no enable switch — availability follows
 * [CommandIsolationConfig.enabled], so a worker with isolation off starts normally and refuses to
 * attach a credential.
 *
 * @property vaultUser Account profile targets run as; must differ from [CommandIsolationConfig.user].
 * @property vaultHelperPath Absolute path of the helper that drops to [vaultUser].
 * @property askpassScriptPath Absolute path of the askpass helper handed to `askpass` profiles.
 * @property secretDir Absolute path of the directory holding one tree per attached profile.
 * @property secretWorkingDirectory Absolute working directory for profile targets; it is not the
 *   shared workspace, so a profile cannot load model-writable configuration.
 * @property maxSecretBytes Largest accepted decrypted secret size.
 */
data class CredentialsConfig(
    val vaultUser: String,
    val vaultHelperPath: String,
    val askpassScriptPath: String,
    val secretDir: String,
    val secretWorkingDirectory: String,
    val maxSecretBytes: Int,
)

/**
 * Resolved policy for running built-in-tool children under a separate OS account.
 *
 * The policy is opt-in: while the switch is off, commands run under the worker's own account. Once
 * it is on, Linux must be able to honour the policy - a missing or unusable mechanism fails startup
 * instead of quietly running commands with the worker's privileges. Platforms without a mechanism
 * ignore the setting, so one configuration file stays portable.
 *
 * @property enabled Whether the worker refuses to start when the mechanism is unusable. Defaults to
 *   `false`.
 * @property user OS account that isolated children run as.
 * @property helperPath Absolute path of the privilege-dropping helper.
 * @property environment Environment policy for children, used only while a privilege drop is active.
 */
data class CommandIsolationConfig(
    val enabled: Boolean,
    val user: String,
    val helperPath: String,
    val environment: CommandEnvironmentConfig,
)

/**
 * Resolved limits and timeouts for interactive terminal sessions.
 *
 * The values bound memory, session count and how long one tool call can block. There is no enable
 * flag: a call that reaches the worker executes, and the capability is controlled by server-side
 * tool enablement.
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
data class TerminalSessionsConfig(
    val defaultIdleTimeoutSeconds: Long,
    val maxIdleTimeoutSeconds: Long,
    val maxLifetimeSeconds: Long,
    val maxSessions: Int,
    val maxClosedSessions: Int,
    val maxOutputBytesPerSession: Int,
    val quietMillis: Long,
    val defaultWaitMillis: Long,
    val maxWaitMillis: Long,
    val reaperIntervalSeconds: Long,
    val closedSessionRetentionSeconds: Long,
    val columns: Int,
    val rows: Int,
    val tuiEscapeRatioThreshold: Double,
)

/**
 * Environment policy applied to children while a privilege drop is active.
 *
 * Children under a different account must not inherit the worker's environment, which may carry
 * credentials; children sharing the worker's account see the same environment as the worker itself.
 *
 * @property allowPassthrough Worker environment variable names the child inherits. A single `*`
 *   entry forwards the whole environment. Account identity variables are always derived from the
 *   isolated account and are not affected by this list.
 * @property set Variables the child receives with these values, independent of the worker's own
 *   environment. Account identity variables are always derived from the isolated account, so an
 *   entry naming `HOME`, `USER` or `LOGNAME` has no effect.
 */
data class CommandEnvironmentConfig(
    val allowPassthrough: List<String>,
    val set: Map<String, String>,
)

/**
 * Filesystem workspace configuration for built-in tools.
 *
 * The workspace is the security boundary for built-in file tools and the working directory for
 * `run_command`. All file paths provided to built-in tools are resolved against this directory and
 * rejected if they escape it.
 *
 * This is the resolved domain model; the path is always an absolute normalized path.
 * For the DTO that accepts relative or absolute paths, see [WorkspaceConfigDto].
 *
 * @property path Absolute normalized path to the workspace directory.
 */
data class WorkspaceConfig(
    val path: String
)

/**
 * Built-in tool configuration.
 *
 * @property enabled List of unprefixed built-in tool names to enable. Empty list enables none.
 *   The sentinel value containing a single `*` enables all registered tools.
 * @property defaultCommandTimeoutSeconds Default timeout in seconds for the `run_command` tool.
 * @property defaultSearchTimeoutSeconds Default timeout in seconds for the `search_text` tool.
 */
data class BuiltInToolsConfig(
    val enabled: List<String> = emptyList(),
    val defaultCommandTimeoutSeconds: Long = 600,
    val defaultSearchTimeoutSeconds: Long = 5
)

/**
 * Authorized signer whose E2EA signatures may be accepted by worker-side verification.
 *
 * The public key is stored in binary form so verification services can consume it without
 * repeatedly decoding configuration text at runtime.
 *
 * @property signerId Stable identifier expected in signed envelopes.
 * @property publicKey Decoded public key bytes used for signature verification.
 * @property permissions Permission tokens granted to signatures produced by this signer.
 */
data class TrustedSigner(
    val signerId: String,
    val publicKey: ByteArray,
    val permissions: List<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedSigner) return false

        return signerId == other.signerId &&
            publicKey.contentEquals(other.publicKey) &&
            permissions == other.permissions
    }

    override fun hashCode(): Int {
        var result = signerId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + permissions.hashCode()
        return result
    }
}

/**
 * Server connection configuration for the worker.
 *
 * @property baseUrl Base URL of the chatbot server.
 */
data class ServerConfig(
    val baseUrl: String
)

/**
 * Worker identity configuration.
 *
 * @property uid Unique identifier for this worker instance.
 * @property displayName Human-readable label for this worker shown in management UIs.
 * @property workerId Numeric worker id pinned by `worker.identity.workerId`; null means no per-row
 *   credential worker-id check.
 * @property certificateFingerprint SHA-256 fingerprint of the worker certificate.
 * @property certificatePem PEM-encoded public certificate.
 */
data class IdentityConfig(
    val uid: String,
    val displayName: String,
    val certificateFingerprint: String,
    val certificatePem: String,
    val workerId: Long? = null
)

/**
 * Storage path configuration.
 *
 * @property secretsJsonPath Path to the secrets JSON file containing the private key.
 * @property tokenFilePath Path to the token cache file.
 */
data class StorageConfig(
    val secretsJsonPath: String,
    val tokenFilePath: String
)

/**
 * Authentication timing configuration.
 *
 * @property refreshSkewSeconds Seconds before token expiry to trigger refresh.
 */
data class AuthConfig(
    val refreshSkewSeconds: Long = 60
)
