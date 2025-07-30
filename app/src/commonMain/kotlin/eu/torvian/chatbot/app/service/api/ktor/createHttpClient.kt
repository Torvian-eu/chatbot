package eu.torvian.chatbot.app.service.api.ktor

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.resources.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Creates and configures a Ktor [HttpClient] with a specific engine factory.
 *
 * @param baseUri The base URI for the API endpoint.
 * @param json The Json instance to use for serialization/deserialization.
 * @param engineFactory The HTTP client engine factory to use.
 * @param logLevel The logging level for HTTP client, defaults to INFO.
 * @return A configured Ktor [HttpClient] instance.
 */
fun <T : HttpClientEngineConfig> createHttpClient(
    baseUri: String,
    json: Json,
    engineFactory: HttpClientEngineFactory<T>,
    logLevel: LogLevel = LogLevel.INFO
): HttpClient {
    return HttpClient(engineFactory) {
        configureClient(baseUri, json, logLevel)
    }
}

/**
 * Creates and configures a Ktor [HttpClient] with a specific engine instance.
 *
 * @param baseUri The base URI for the API endpoint.
 * @param json The Json instance to use for serialization/deserialization.
 * @param engine The HTTP client engine instance to use.
 * @param logLevel The logging level for HTTP client, defaults to INFO.
 * @return A configured Ktor [HttpClient] instance.
 */
fun createHttpClient(
    baseUri: String,
    json: Json,
    engine: HttpClientEngine,
    logLevel: LogLevel = LogLevel.INFO
): HttpClient {
    return HttpClient(engine) {
        configureClient(baseUri, json, logLevel)
    }
}

/**
 * Factory function to create and configure the Ktor [HttpClient] for frontend-backend communication.
 *
 * The HTTP client engine is selected based on the artifacts added in a build script.
 *
 * This client is configured for:
 * - automatic JSON serialization/deserialization
 * - default request configuration (base URL and content type)
 * - type-safe requests using the Resources plugin
 * - default response validation (throws exceptions for non-successful responses)
 * - configurable logging level
 *
 * @param baseUri The base URI for the API endpoint.
 * @param json The Json instance to use for serialization/deserialization.
 * @param logLevel The logging level for HTTP client, defaults to INFO.
 * @return A configured Ktor [HttpClient] instance.
 */
fun createHttpClient(
    baseUri: String,
    json: Json,
    logLevel: LogLevel = LogLevel.INFO
): HttpClient {
    // The HttpClient constructor is called without an argument:
    // An engine is selected based on the artifacts added in a build script.
    // See: https://ktor.io/docs/client-engines.html#default
    return HttpClient {
        configureClient(baseUri, json, logLevel)
    }
}

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
fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureClient(
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
        logger = Logger.DEFAULT
        level = logLevel
    }
}