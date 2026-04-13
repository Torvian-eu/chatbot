package eu.torvian.chatbot.worker.config

import kotlinx.serialization.Serializable

/**
 * Runtime configuration for the standalone worker process.
 *
 * @property serverBaseUrl Base URL of the chatbot server to connect to.
 * @property workerUid Unique string identifier of the worker instance as registered on the server.
 * @property certificateFingerprint SHA-256 fingerprint of the worker's TLS client certificate.
 * @property secretsJsonPath Filesystem path to the JSON secrets file containing the certificate and private key.
 * @property tokenFilePath Filesystem path to the service token cache file.
 * @property refreshSkewSeconds Time in seconds before token expiration to proactively refresh the token.
 */
@Serializable
data class WorkerRuntimeConfig(
    val serverBaseUrl: String,
    val workerUid: String,
    val certificateFingerprint: String,
    val secretsJsonPath: String,
    val tokenFilePath: String,
    val refreshSkewSeconds: Long = 60
)

