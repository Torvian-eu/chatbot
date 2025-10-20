package eu.torvian.chatbot.app.service.api.ktor

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.sse.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Extension function to configure a Ktor [HttpClientConfig] with standard settings.
 *
 * Configures:
 * - Content negotiation with JSON
 * - Default request settings (base URL and content type)
 * - Resources plugin for type-safe requests
 * - Response validation
 * - Logging with configurable level
 *
 * @param baseUri The base URI for the API endpoint.
 * @param json The Json instance to use for serialization/deserialization.
 * @param logLevel The logging level for HTTP client, defaults to INFO.
 */
fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureHttpClient(
    baseUri: String,
    json: Json,
    logLevel: LogLevel = LogLevel.INFO
) {
    // Install Content Negotiation plugin for automatic JSON serialization/deserialization
    install(ContentNegotiation) {
        json(json)
    }

    // Configure default requests to point to the embedded server
    defaultRequest {
        url(baseUri)
        contentType(ContentType.Application.Json)
    }

    // Install the Resources plugin for type-safe requests
    install(Resources)

    // Enable default response validation
    // The following exceptions will be thrown if the response status is not successful:
    // - RedirectResponseException for 3xx responses
    // - ClientRequestException for 4xx responses
    // - ServerResponseException for 5xx responses
    expectSuccess = true

    // Add logging for debugging API calls
    install(Logging) {
        logger = Logger.Companion.DEFAULT
        level = logLevel
    }

    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }

    install(SSE)
}