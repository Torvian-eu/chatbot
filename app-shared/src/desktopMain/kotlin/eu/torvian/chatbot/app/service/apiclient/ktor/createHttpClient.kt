package eu.torvian.chatbot.app.service.apiclient.ktor

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Factory function to create and configure the Ktor HttpClient for frontend-backend communication.
 *
 * This client is configured for JSON content negotiation and points to the embedded Ktor server
 * running at the specified base URL.
 *
 * This function is placed in `desktopMain` because it uses the JVM-specific [CIO] engine.
 *
 * @param baseUri The base URI for the API endpoint.
 * @return A configured Ktor [HttpClient] instance.
 */
actual fun createHttpClient(baseUri: String): HttpClient {
    return HttpClient(CIO) { // Using CIO engine, which is JVM-specific
        // Install Content Negotiation plugin for automatic JSON serialization/deserialization
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true // For debugging readability
                ignoreUnknownKeys = true // Be lenient with extra fields in JSON
                // Add other configuration like explicit encoders/decoders if needed
            })
        }
        // Configure default requests to point to the embedded server
        defaultRequest {
            url(baseUri)
            contentType(ContentType.Application.Json)
        }
        // Optional: Add logging for debugging API calls
        // install(Logging) {
        //     logger = Logger.DEFAULT
        //     level = LogLevel.INFO // Or DEBUG for full request/response bodies
        // }
    }
}