package eu.torvian.chatbot.app.service.api.ktor

import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.app.service.security.CertificateTrustService
import io.ktor.client.*
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.json.Json

/**
 * Creates and configures a Ktor [HttpClient] with platform-specific SSL/TLS handling
 * and shared plugin configuration. This is the new primary factory for creating clients.
 *
 * @param baseUri The base URI for the API endpoint.
 * @param json The Json instance to use for serialization/deserialization.
 * @param logLevel The logging level for HTTP client.
 * @param certificateStorage The platform-specific storage for trusted certificates.
 * @param certificateTrustService The service for handling user trust prompts.
 * @return A configured Ktor [HttpClient] instance.
 */
expect fun createPlatformHttpClient(
    baseUri: String,
    json: Json,
    logLevel: LogLevel,
    certificateStorage: CertificateStorage,
    certificateTrustService: CertificateTrustService
): HttpClient

