package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
    private val strategies: Map<LLMProviderType, ChatCompletionStrategy>,
    private val modelDiscoveryStrategies: Map<LLMProviderType, ModelDiscoveryStrategy> = emptyMap()
) : LLMApiClient {
    companion object {
        private val logger: Logger = LogManager.getLogger(LLMApiClientKtor::class.java)
    }

    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
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
            apiKey = apiKey,
            tools = tools
        ).getOrElse { error -> // Handle ConfigurationError returned by the strategy
            logger.error("Strategy ${strategy::class.simpleName} failed to prepare request: ${error.message}")
            return error.left() // Propagate the specific error returned by the strategy
        }

        // 3. Execute the HTTP call using the HttpClient based on the generic config.
        // This is where generic types are mapped to Ktor types.
        return withContext(Dispatchers.IO) {
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
                    timeout {
                        requestTimeoutMillis = 180_000 // 3 minutes
                    }
                }
                logger.debug("Received HTTP response: ${httpResponse.status}")

                // 4. Read the response body as text. This is done regardless of status
                // so the strategy can process the raw body for both success and error responses.
                val responseBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    // Handle errors specifically during reading the response body
                    logger.error("Failed to read response body from ${provider.name} API", e)
                    return@withContext LLMCompletionError.NetworkError(
                        "Failed to read response body from ${provider.name}",
                        e
                    ).left()
                }
                logger.debug("Response body read successfully (length: ${responseBody.length})")

                // 5. Process the response using the strategy based on the HTTP status code.
                return@withContext if (httpResponse.status.isSuccess()) {
                    // If status is 2xx, delegate success processing to the strategy
                    logger.debug("Processing successful response with strategy ${strategy::class.simpleName}")
                    strategy.processSuccessResponse(responseBody)
                        .getOrElse { error -> // Handle InvalidResponseError from strategy (parsing/mapping failure)
                            logger.error(
                                "Strategy ${strategy::class.simpleName} failed to process success response: ${error.message}",
                                error.cause
                            )
                            return@withContext error.left() // Propagate the specific error
                        }
                        .right() // Wrap the final generic result in Right

                } else {
                    // If status is non-2xx, delegate error processing to the strategy
                    logger.debug("Processing error response with strategy ${strategy::class.simpleName}")
                    val apiError = strategy.processErrorResponse(httpResponse.status.value, responseBody)
                    logger.error("LLM API ${provider.name} returned error (Status: ${httpResponse.status.value}): $apiError")
                    apiError.left() // Return the specific API error provided by the strategy
                }

            } catch (e: Exception) {
                // Catch any exceptions that occurred during the HTTP request itself
                // (e.g., network issues, connection refused, unexpected errors before status/body is available).
                logger.error("LLMApiClientKtor: HTTP request failed for provider ${provider.name}", e)
                LLMCompletionError.NetworkError(
                    "Network or communication error with ${provider.name}: ${e.message}",
                    e
                ).left()
            }
        }
    }

    override fun completeChatStreaming(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
    ): Flow<Either<LLMCompletionError, LLMStreamChunk>> = channelFlow {
        logger.info("LLMApiClientKtor: Received streaming request for model ${modelConfig.name} (Provider: ${provider.name}, Type: ${provider.type})")

        val strategy = strategies[provider.type]
            ?: run {
                val errorMsg = "No ChatCompletionStrategy found for provider type: ${provider.type}"
                logger.error(errorMsg)
                send(LLMCompletionError.ConfigurationError(errorMsg).left())
                return@channelFlow
            }

        val apiRequestConfig = strategy.prepareRequest(
            messages = messages,
            modelConfig = modelConfig,
            provider = provider,
            settings = settings,
            apiKey = apiKey,
            tools = tools
        ).getOrElse { error ->
            logger.error("Strategy ${strategy::class.simpleName} failed to prepare streaming request: ${error.message}")
            send(error.left())
            return@channelFlow
        }

        try {
            logger.debug("Executing HTTP streaming request: ${apiRequestConfig.method} ${provider.baseUrl}${apiRequestConfig.path}")

            // Use preparePost and execute to get a streaming response
            httpClient.prepareRequest("${provider.baseUrl}${apiRequestConfig.path}") {
                method = apiRequestConfig.method.toKtorHttpMethod()
                contentType(apiRequestConfig.contentType.toKtorContentType())
                headers {
                    apiRequestConfig.customHeaders.forEach { (key, value) ->
                        append(key, value)
                    }
                }
                setBody(apiRequestConfig.body)
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS // Allow indefinite stream
                }
            }.execute { httpResponse -> // Use execute block for streaming
                if (httpResponse.status.isSuccess()) {
                    logger.debug("Received HTTP streaming response: ${httpResponse.status}")

                    // Get the ByteReadChannel for raw content
                    val byteReadChannel: ByteReadChannel = httpResponse.body()

                    // Transform ByteReadChannel into a Flow of lines
                    val responseStream: Flow<String> = channelFlow {
                        while (!byteReadChannel.isClosedForRead) {
                            val line = byteReadChannel.readLineStrict(limit = 10_240)
                            if (line != null) {
                                send(line)
                            } else {
                                break // End of stream
                            }
                        }
                    }

                    // Process the stream using the strategy
                    strategy.processStreamingResponse(responseStream).collect { chunkEither ->
                        send(chunkEither) // Forward each processed chunk to the downstream flow
                    }
                } else {
                    val errorBody = try {
                        httpResponse.bodyAsText()
                    } catch (e: Exception) {
                        logger.error("Failed to read error response body from ${provider.name} API", e)
                        "Failed to read error body: ${e.message}"
                    }
                    logger.debug("Processing error response (streaming) with strategy ${strategy::class.simpleName}")
                    val apiError = strategy.processErrorResponse(httpResponse.status.value, errorBody)
                    logger.error("LLM API ${provider.name} returned error (Streaming Status: ${httpResponse.status.value}): $apiError")
                    send(apiError.left())
                }
            }
        } catch (e: Exception) {
            logger.error("LLMApiClientKtor: HTTP streaming request failed for provider ${provider.name}", e)
            send(
                LLMCompletionError.NetworkError(
                    "Network or communication error with ${provider.name}: ${e.message}",
                    e
                ).left()
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun discoverModels(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError, ModelDiscoveryResult> {
        logger.info("LLMApiClientKtor: Received model discovery request for provider ${provider.name} (Type: ${provider.type})")

        val strategy = modelDiscoveryStrategies[provider.type]
            ?: run {
                val errorMsg = "No ModelDiscoveryStrategy found for provider type: ${provider.type}"
                logger.error(errorMsg)
                return ModelDiscoveryError.ConfigurationError(errorMsg).left()
            }

        val apiRequestConfig = strategy.prepareRequest(provider, apiKey).getOrElse { error ->
            logger.error("Strategy ${strategy::class.simpleName} failed to prepare model discovery request: ${error.message}")
            return error.left()
        }

        return withContext(Dispatchers.IO) {
            try {
                logger.debug("Executing model discovery request: ${apiRequestConfig.method} ${provider.baseUrl}${apiRequestConfig.path}")
                val httpResponse: HttpResponse = httpClient.request {
                    method = apiRequestConfig.method.toKtorHttpMethod()
                    url("${provider.baseUrl}${apiRequestConfig.path}")
                    contentType(apiRequestConfig.contentType.toKtorContentType())
                    headers {
                        apiRequestConfig.customHeaders.forEach { (key, value) ->
                            append(key, value)
                        }
                    }
                    if (apiRequestConfig.method != GenericHttpMethod.GET) {
                        setBody(apiRequestConfig.body)
                    }
                    timeout {
                        requestTimeoutMillis = 60_000
                    }
                }

                val responseBody = try {
                    httpResponse.bodyAsText()
                } catch (e: Exception) {
                    logger.error("Failed to read model discovery response body from ${provider.name}", e)
                    return@withContext ModelDiscoveryError.NetworkError(
                        "Failed to read response body from ${provider.name}",
                        e
                    ).left()
                }

                if (httpResponse.status.isSuccess()) {
                    strategy.processSuccessResponse(responseBody)
                        .getOrElse { error ->
                            logger.error(
                                "Strategy ${strategy::class.simpleName} failed to process model discovery response: ${error.message}",
                                error.cause
                            )
                            return@withContext error.left()
                        }
                        .right()
                } else {
                    val apiError = strategy.processErrorResponse(httpResponse.status.value, responseBody)
                    logger.error(
                        "LLM API ${provider.name} returned model discovery error (Status: ${httpResponse.status.value}): $apiError"
                    )
                    apiError.left()
                }
            } catch (e: Exception) {
                logger.error("LLMApiClientKtor: Model discovery request failed for provider ${provider.name}", e)
                ModelDiscoveryError.NetworkError(
                    "Network or communication error with ${provider.name}: ${e.message}",
                    e
                ).left()
            }
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
