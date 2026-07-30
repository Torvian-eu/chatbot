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
 * @property trustedSigners Authorized E2EA signers trusted by this worker.
 */
data class RuntimeConfig(
    val server: ServerConfig,
    val identity: IdentityConfig,
    val storage: StorageConfig,
    val auth: AuthConfig,
    val workspace: WorkspaceConfig,
    val builtInTools: BuiltInToolsConfig,
    val trustedSigners: List<TrustedSigner>
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
 * @property certificateFingerprint SHA-256 fingerprint of the worker certificate.
 * @property certificatePem PEM-encoded public certificate.
 */
data class IdentityConfig(
    val uid: String,
    val displayName: String,
    val certificateFingerprint: String,
    val certificatePem: String
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
