package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Ktor implementation of the [LLMApiClient].
 * This class handles the actual HTTP communication using Ktor.
 * It delegates the provider-specific request preparation and response processing
 * to injected [ChatCompletionStrategy] instances.
 *
 * Requires HttpClient to be configured with the ContentNegotiation feature
 * and appropriate serializers (e.g., KotlinxSerializationConverter for JSON).
 *
 * @property httpClient Injected Ktor HttpClient (configured for serialization)
 * @property strategies Injected map of strategies
 */
class LLMApiClientKtor(
    private val httpClient: HttpClient,
    private val strategies: Map<LLMProviderType, ChatCompletionStrategy>
) : LLMApiClient {

    companion object {
        private val logger: Logger = LogManager.getLogger(LLMApiClientKtor::class.java)
    }

    override suspend fun completeChat(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?
    ): Either<LLMCompletionError, LLMCompletionResult> {

        logger.info("LLMApiClientKtor: Received request for model ${modelConfig.name} (Provider: ${provider.name}, Type: ${provider.type})")
        logger.debug("Context messages received: ${messages.size}")

        // 1. Find the appropriate strategy for the provider type.
        // If no strategy is found, return a configuration error immediately.
        val strategy = strategies[provider.type]
            ?: run {
                val errorMsg = "No ChatCompletionStrategy found for provider type: ${provider.type}"
                logger.error(errorMsg)
                return LLMCompletionError.ConfigurationError(errorMsg).left()
            }
        logger.debug("Using strategy: ${strategy::class.simpleName} for provider type ${provider.type}")

        // 2. Use the selected strategy to prepare the generic API request configuration.
        // This involves mapping application models to API-specific request DTOs.
        val apiRequestConfig = strategy.prepareRequest(
            messages = messages,
            modelConfig = modelConfig,
            provider = provider,
            settings = settings,
            apiKey = apiKey
        ).getOrElse { error -> // Handle ConfigurationError returned by the strategy
            logger.error("Strategy ${strategy::class.simpleName} failed to prepare request: ${error.message}")
            return error.left() // Propagate the specific error returned by the strategy
        }

        // 3. Execute the HTTP call using the HttpClient based on the generic config.
        // This is where generic types are mapped to Ktor types.
        try {
            logger.debug("Executing HTTP request: ${apiRequestConfig.method} ${provider.baseUrl}${apiRequestConfig.path}")
            val httpResponse: HttpResponse = httpClient.request {
                // Set the HTTP method
                method = apiRequestConfig.method.toKtorHttpMethod()
                // Build the full URL by combining base URL and path
                url("${provider.baseUrl}${apiRequestConfig.path}")
                // Set the content type header using the mapped Ktor type
                contentType(apiRequestConfig.contentType.toKtorContentType())
                // Add any custom headers specified by the strategy
                headers {
                    apiRequestConfig.customHeaders.forEach { (key, value) ->
                        append(key, value)
                    }
                }
                // Set the request body. HttpClient's ContentNegotiation feature will
                // automatically serialize the object based on the content type.
                setBody(apiRequestConfig.body)
            }
            logger.debug("Received HTTP response: ${httpResponse.status}")

            // 4. Read the response body as text. This is done regardless of status
            // so the strategy can process the raw body for both success and error responses.
            val responseBody = try {
                httpResponse.bodyAsText()
            } catch (e: Exception) {
                // Handle errors specifically during reading the response body
                logger.error("Failed to read response body from ${provider.name} API", e)
                return LLMCompletionError.NetworkError("Failed to read response body from ${provider.name}", e).left()
            }
            logger.debug("Response body read successfully (length: ${responseBody.length})")

            // 5. Process the response using the strategy based on the HTTP status code.
            return if (httpResponse.status.isSuccess()) {
                // If status is 2xx, delegate success processing to the strategy
                logger.debug("Processing successful response with strategy ${strategy::class.simpleName}")
                strategy.processSuccessResponse(responseBody)
                    .getOrElse { error -> // Handle InvalidResponseError from strategy (parsing/mapping failure)
                        logger.error(
                            "Strategy ${strategy::class.simpleName} failed to process success response: ${error.message}",
                            error.cause
                        )
                        return error.left() // Propagate the specific error
                    }
                    .right() // Wrap the final generic result in Right

            } else {
                // If status is non-2xx, delegate error processing to the strategy
                logger.debug("Processing error response with strategy ${strategy::class.simpleName}")
                val apiError = strategy.processErrorResponse(httpResponse.status.value, responseBody)
                logger.error("LLM API ${provider.name} returned error (Status: ${httpResponse.status.value}): ${apiError}")
                return apiError.left() // Return the specific API error provided by the strategy
            }

        } catch (e: Exception) {
            // Catch any exceptions that occurred during the HTTP request itself
            // (e.g., network issues, connection refused, unexpected errors before status/body is available).
            logger.error("LLMApiClientKtor: HTTP request failed for provider ${provider.name}", e)
            return LLMCompletionError.NetworkError(
                "Network or communication error with ${provider.name}: ${e.message}",
                e
            ).left()
        }
    }
}

// --- Helper functions ---

/**
 * Converts a [GenericHttpMethod] to a Ktor [HttpMethod].
 */
private fun GenericHttpMethod.toKtorHttpMethod(): HttpMethod = when (this) {
    GenericHttpMethod.GET -> HttpMethod.Get
    GenericHttpMethod.POST -> HttpMethod.Post
    GenericHttpMethod.PUT -> HttpMethod.Put
    GenericHttpMethod.DELETE -> HttpMethod.Delete
    GenericHttpMethod.PATCH -> HttpMethod.Patch
    GenericHttpMethod.HEAD -> HttpMethod.Head
    GenericHttpMethod.OPTIONS -> HttpMethod.Options
}

/**
 * Converts a [GenericContentType] to a Ktor [ContentType].
 */
private fun GenericContentType.toKtorContentType(): ContentType = ContentType.parse(this.contentType)
