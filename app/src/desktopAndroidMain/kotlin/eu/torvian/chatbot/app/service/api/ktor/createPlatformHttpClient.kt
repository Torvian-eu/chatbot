package eu.torvian.chatbot.app.service.api.ktor

import eu.torvian.chatbot.app.service.security.CertificateStorage
import eu.torvian.chatbot.app.service.security.CertificateTrustService
import eu.torvian.chatbot.app.service.security.CustomTrustManager
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.logging.*
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

actual fun createPlatformHttpClient(
    baseUri: String,
    json: Json,
    logLevel: LogLevel,
    certificateStorage: CertificateStorage,
    certificateTrustService: CertificateTrustService
): HttpClient {
    return HttpClient(OkHttp) {
        // 1. Apply all the shared configuration (logging, content negotiation, etc.)
        configureHttpClient(baseUri, json, logLevel)

        // 2. Apply the JVM-specific engine configuration for OkHttp
        engine {
            val customTrustManager = CustomTrustManager(
                certificateStorage = certificateStorage,
                serverUrl = baseUri,
                trustService = certificateTrustService
            )
            val sslSocketFactory = createSslSocketFactory(customTrustManager)
            config {
                sslSocketFactory(sslSocketFactory, customTrustManager as X509TrustManager)
                // Optionally, add hostname verification:
                // hostnameVerifier { hostname, session -> true }
            }
        }
    }
}

/**
 * Helper function to create an SSLSocketFactory from a custom X509TrustManager.
 * This is the standard way to configure custom trust logic for OkHttp and other JVM-based HTTP clients.
 *
 * @param trustManager The custom trust manager to use.
 * @return An SSLSocketFactory configured with the custom trust manager.
 */
private fun createSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    return sslContext.socketFactory
}