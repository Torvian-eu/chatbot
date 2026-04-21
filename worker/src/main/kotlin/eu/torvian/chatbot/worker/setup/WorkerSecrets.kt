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