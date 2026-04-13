package eu.torvian.chatbot.worker.setup

import kotlinx.serialization.Serializable

/**
 * PEM-backed worker identity material persisted during setup.
 *
 * This payload is stored in `secrets.json` and contains the certificate/private-key pair
 * plus the precomputed certificate fingerprint used by the worker and server.
 *
 * @property certificatePem PEM-encoded self-signed certificate.
 * @property privateKeyPem PEM-encoded private key paired with the certificate.
 * @property certificateFingerprint SHA-256 fingerprint derived from the certificate.
 */
@Serializable
data class WorkerSecrets(
    val certificatePem: String,
    val privateKeyPem: String,
    val certificateFingerprint: String
)

/**
 * Credentials used by setup to authenticate as a user while registering the worker.
 *
 * These values are obtained from environment variables or an interactive terminal prompt
 * and are used only for the setup-time login/logout sequence.
 *
 * @property username User account name used to log in to the server.
 * @property password Plaintext password used to authenticate the user.
 */
data class WorkerSetupCredentials(
    val username: String,
    val password: String
)

