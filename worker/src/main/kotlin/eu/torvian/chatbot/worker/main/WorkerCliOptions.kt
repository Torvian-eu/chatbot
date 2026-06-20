package eu.torvian.chatbot.worker.main

/**
 * Parsed command-line options for the standalone worker process.
 *
 * @property configPathOverride Optional path to a custom configuration directory, overriding the default location.
 * @property runOnce If true, the worker will execute its main task once and then exit, instead of running continuously. Useful for testing or one-off operations.
 * @property setup If true, the worker runs the initial setup flow instead of the normal runtime.
 * @property serverUrlOverride Optional server URL override for setup mode.
 * @property addTrustedSigner If true, the worker runs trusted-signer admin mode and exits after updating `application.json`.
 * @property signerId Optional signer identifier used by trusted-signer admin mode.
 * @property publicKeyBase64 Optional Base64-encoded signer public key used by trusted-signer admin mode.
 * @property permissionsCsv Optional comma-separated permission list used by trusted-signer admin mode.
 */
data class WorkerCliOptions(
    val configPathOverride: String? = null,
    val runOnce: Boolean = false,
    val setup: Boolean = false,
    val serverUrlOverride: String? = null,
    val addTrustedSigner: Boolean = false,
    val signerId: String? = null,
    val publicKeyBase64: String? = null,
    val permissionsCsv: String? = null
)

