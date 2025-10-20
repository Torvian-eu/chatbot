package eu.torvian.chatbot.app.service.api.ktor

import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.app.service.security.CertificateTrustService
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.json.Json

actual fun createPlatformHttpClient(
    baseUri: String,
    json: Json,
    logLevel: LogLevel,
    certificateStorage: CertificateStorage,
    certificateTrustService: CertificateTrustService
): HttpClient {
    // For WasmJs, the browser's networking stack handles all SSL/TLS validation.
    // We just need to create the client and apply our shared configuration.
    return HttpClient(CIO) {
        // Apply all the shared configuration. No special engine block is needed.
        configureHttpClient(baseUri, json, logLevel)
    }
}

