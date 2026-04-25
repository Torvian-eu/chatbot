package eu.torvian.chatbot.worker.setup

import kotlinx.serialization.Serializable

/**
 * Sensitive worker identity material persisted during setup.
 *
 * This payload is stored in `secrets.json` and contains only the private key.
 * Public certificate and fingerprint live in the normal configuration file.
 *
 * @property privateKeyPem PEM-encoded private key paired with the worker certificate.
 */
@Serializable
data class Secrets(
    val privateKeyPem: String
)
