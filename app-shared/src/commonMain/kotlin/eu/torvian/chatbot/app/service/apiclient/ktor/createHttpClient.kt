package eu.torvian.chatbot.app.service.apiclient.ktor

import io.ktor.client.HttpClient

/**
 * Factory function to create and configure the Ktor HttpClient for frontend-backend communication.
 *
 * This client is configured for JSON content negotiation and points to the specified base URI.
 *
 * This function is `expect`ed in `commonMain` and `actual`ly implemented in `jvmMain` and `jsMain`
 * to accommodate platform-specific needs, such as different HTTP engines.
 *
 * @param baseUri The base URI the client should point to.
 * @return A configured Ktor [HttpClient] instance.
 */
expect fun createHttpClient(baseUri: String): HttpClient