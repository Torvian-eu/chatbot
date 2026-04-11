package eu.torvian.chatbot.worker.config

import kotlinx.serialization.Serializable

/**
 * Runtime configuration for the standalone worker process.
 *
 * @property serverBaseUrl Base URL of the chatbot server to connect to.
 * @property workerId Unique identifier of the worker instance as registered on the server.
 * @property certificateFingerprint SHA-256 fingerprint of the worker's TLS client certificate.
 * @property privateKeyPemPath Filesystem path to the PEM-encoded private key for signing auth challenges.
 * @property tokenFilePath Filesystem path to the service token cache file.
 * @property refreshSkewSeconds Time in seconds before token expiration to proactively refresh the token.
 */
@Serializable
data class WorkerRuntimeConfig(
    val serverBaseUrl: String,
    val workerId: Long,
    val certificateFingerprint: String,
    val privateKeyPemPath: String,
    val tokenFilePath: String,
    val refreshSkewSeconds: Long = 60
)

