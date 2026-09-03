package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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
    private val modelDiscoveryStrategies: Map<LLMProviderType, ModelDiscoveryStrategy> = emptyMap(),
    private val responsesStrategy: ChatCompletionStrategy? = null
) : LLMApiClient {
    companion object {
        private val logger: Logger = LogManager.getLogger(LLMApiClientKtor::class.java)

        /**
         * Maximum number of raw bytes accepted for a non-streaming LLM response.
         *
         * This is deliberately a transport limit rather than an assistant-content limit;
         * it protects the server while leaving content and persistence policy to higher layers.
         */
        private const val MAX_LLM_NON_STREAMING_RESPONSE_BYTES: Long = 10L * 1024L * 1024L

        /**
         * Maximum number of raw bytes accepted for one streaming LLM response.
         *
         * Streaming responses receive a larger allowance because their complete payload is
         * distributed over time, but the limit still bounds the total provider data consumed.
         */
        private const val MAX_LLM_STREAMING_RESPONSE_BYTES: Long = 50L * 1024L * 1024L
    }

    /**
     * Shared strategy-selection rule so the counting path and the request path resolve dialects identically.
     */
    private val strategyResolver = ChatCompletionStrategyResolver(strategies, responsesStrategy)

    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Either<LLMCompletionError, LLMCompletionResult> {

        logger.info("LLMApiClientKtor: Received request for model ${modelConfig.name} (Provider: ${provider.name}, Type: ${provider.type})")
        logger.debug("Context messages received: ${messages.size}")

        // 1. Find the appropriate strategy for this model and provider type.
        // If no strategy is found, return a configuration error immediately.
        val strategy = resolveStrategy(settings, provider)
            ?: run {
                val errorMsg = "No ChatCompletionStrategy found for provider type: ${provider.type}"
                logger.error(errorMsg)
                return LLMCompletionError.ConfigurationError(errorMsg).left()
            }
        logger.debug("Using strategy: {} for provider type {}", strategy::class.simpleName, provider.type)

        // 2. Use the selected strategy to prepare the generic API request configuration.
        // This involves mapping application models to API-specific request DTOs.
        val apiRequestConfig = strategy.prepareRequest(
            messages = messages,
            modelConfig = modelConfig,
            provider = provider,
            settings = settings,
            apiKey = apiKey,
            tools = tools,
            systemMessage = systemMessage
        ).getOrElse { error -> // Handle ConfigurationError returned by the strategy
            logger.error("Strategy {} failed to prepare request: {}", strategy::class.simpleName, error.message)
            return error.left() // Propagate the specific error returned by the strategy
        }

        // 3. Execute the HTTP call using the HttpClient based on the generic config.
        // This is where generic types are mapped to Ktor types.
        return withContext(Dispatchers.IO) {
            try {
                logger.debug(
                    "Executing HTTP request: {} {}",
                    apiRequestConfig.method,
                    "${provider.baseUrl}${apiRequestConfig.path}"
                )
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
                logger.debug("Received HTTP response: {}", httpResponse.status)

                // Read through the raw channel so a provider cannot force an unbounded response allocation.
                // The same bounded body is used for success and error strategies to keep transport behavior uniform.
                val responseBody = try {
                    val byteReadChannel: ByteReadChannel = httpResponse.body()
                    readUtf8BodyLimited(
                        channel = byteReadChannel,
                        maximumBytes = MAX_LLM_NON_STREAMING_RESPONSE_BYTES
                    )
                } catch (e: ResponseBodyLimitExceededException) {
                    val errorMessage =
                        "Response from ${provider.name} exceeded the configured transport limit of " +
                                "$MAX_LLM_NON_STREAMING_RESPONSE_BYTES bytes"
                    logger.error(errorMessage, e)
                    return@withContext LLMCompletionError.OtherError(errorMessage).left()
                } catch (e: CharacterCodingException) {
                    val errorMessage = "Response from ${provider.name} was not valid UTF-8"
                    logger.error(errorMessage, e)
                    return@withContext LLMCompletionError.InvalidResponseError(errorMessage, e).left()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Preserve the existing typed transport error for channel and decoding failures.
                    logger.error("Failed to read response body from {} API", provider.name, e)
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
                if (e is CancellationException) throw e
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
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Flow<Either<LLMCompletionError, LLMStreamChunk>> = channelFlow {
        logger.info("LLMApiClientKtor: Received streaming request for model ${modelConfig.name} (Provider: ${provider.name}, Type: ${provider.type})")

        val strategy = resolveStrategy(settings, provider)
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
            tools = tools,
            systemMessage = systemMessage
        ).getOrElse { error ->
            logger.error("Strategy ${strategy::class.simpleName} failed to prepare streaming request: ${error.message}")
            send(error.left())
            return@channelFlow
        }

        try {
            logger.debug(
                "Executing HTTP streaming request: {} {}{}",
                apiRequestConfig.method,
                provider.baseUrl,
                apiRequestConfig.path
            )

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
                    logger.debug("Received HTTP streaming response: {}", httpResponse.status)

                    // Get the ByteReadChannel for raw content.
                    val byteReadChannel: ByteReadChannel = httpResponse.body()

                    val responseStream = readUtf8LinesLimited(
                        channel = byteReadChannel,
                        maximumBytes = MAX_LLM_STREAMING_RESPONSE_BYTES,
                        logger = logger
                    )

                    // Process the stream using the strategy
                    strategy.processStreamingResponse(responseStream).collect { chunkEither ->
                        send(chunkEither) // Forward each processed chunk to the downstream flow
                    }
                } else {
                    val errorBody = readUtf8BodyLimited(
                        channel = httpResponse.body(),
                        maximumBytes = MAX_LLM_STREAMING_RESPONSE_BYTES
                    )
                    logger.debug("Processing error response (streaming) with strategy ${strategy::class.simpleName}")
                    val apiError = strategy.processErrorResponse(httpResponse.status.value, errorBody)
                    logger.error("LLM API ${provider.name} returned error (Streaming Status: ${httpResponse.status.value}): $apiError")
                    send(apiError.left())
                }
            }
        } catch (e: ResponseBodyLimitExceededException) {
            val errorMessage =
                "Streaming response from ${provider.name} exceeded the configured transport limit of " +
                        "$MAX_LLM_STREAMING_RESPONSE_BYTES bytes"
            logger.error(errorMessage, e)
            send(LLMCompletionError.OtherError(errorMessage).left())
        } catch (e: CharacterCodingException) {
            val errorMessage = "Streaming response from ${provider.name} was not valid UTF-8"
            logger.error(errorMessage, e)
            send(LLMCompletionError.InvalidResponseError(errorMessage, e).left())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
                logger.debug(
                    "Executing model discovery request: {} {}{}",
                    apiRequestConfig.method,
                    provider.baseUrl,
                    apiRequestConfig.path
                )
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
                if (e is CancellationException) throw e
                logger.error("LLMApiClientKtor: Model discovery request failed for provider ${provider.name}", e)
                ModelDiscoveryError.NetworkError(
                    "Network or communication error with ${provider.name}: ${e.message}",
                    e
                ).left()
            }
        }
    }

    /**
     * Resolves the [ChatCompletionStrategy] for a chat request.
     *
     * The strategy is chosen from the concrete [ModelSettings] subtype, because the settings profile
     * decides the API dialect for the invocation: RESPONSES settings (OpenAI Responses API) are routed
     * to the Responses strategy when one is registered; all other settings use the strategy registered
     * for the provider type. This allows a single model to serve both Chat Completions and Responses
     * requests through different settings profiles attached to it.
     *
     * @param settings The settings profile attached to the request, which determines the API dialect.
     * @param provider The owning provider.
     * @return The resolved strategy, or `null` if none is registered for the request.
     */
    private fun resolveStrategy(
        settings: ModelSettings,
        provider: LLMProvider,
    ): ChatCompletionStrategy? = strategyResolver.resolve(settings, provider)
}


// --- Helper functions ---

/**
 * Size of the temporary transport buffer used while reading provider response bytes.
 */
private const val RESPONSE_READ_BUFFER_SIZE: Int = 8 * 1024

/**
 * Reads UTF-8 response lines while enforcing a cumulative raw-byte upper bound.
 *
 * Line delimiters are counted as transport bytes, including the LF in LF and both bytes
 * in CRLF. The helper deliberately reads bytes itself because an unbounded line reader
 * could allocate a large unterminated line before the cumulative limit is checked.
 *
 * @param channel The single-reader response channel to consume.
 * @param maximumBytes The inclusive maximum number of raw response bytes to accept.
 * @return A cold flow of complete lines without their line delimiters.
 * @throws ResponseBodyLimitExceededException If the response exceeds [maximumBytes].
 * @throws CharacterCodingException If a line is not valid UTF-8.
 * @throws IOException If the response channel closes with an I/O failure.
 */
private fun readUtf8LinesLimited(
    channel: ByteReadChannel,
    maximumBytes: Long,
    logger: Logger
): Flow<String> = flow {
    require(maximumBytes > 0) { "maximumBytes must be positive" }

    // Temporary diagnostics, removed once the root cause is fixed. Accumulates the decoded lines so
    // a suspiciously short stream (provider closed the connection early) can be logged verbatim.
    val diagnosticText = StringBuilder()
    val readBuffer = ByteArray(RESPONSE_READ_BUFFER_SIZE)
    var readBufferOffset = 0
    var readBufferSize = 0
    var totalResponseBytes = 0L
    val lineBytes = ByteArrayOutputStream()

    while (true) {
        lineBytes.reset()
        var lineFinished = false
        var lineHasInput = false

        while (!lineFinished) {
            if (readBufferOffset >= readBufferSize) {
                val remainingBytes = maximumBytes - totalResponseBytes
                val bytesToRead = when {
                    remainingBytes >= readBuffer.size.toLong() -> readBuffer.size
                    remainingBytes > 0 -> (remainingBytes + 1L).toInt()
                    else -> 1
                }
                val bytesRead = channel.readAvailable(readBuffer, 0, bytesToRead)
                if (bytesRead == -1) {
                    channel.closedCause?.let { throw it }
                    break
                }
                if (bytesRead == 0) continue
                readBufferOffset = 0
                readBufferSize = bytesRead
            }

            while (readBufferOffset < readBufferSize && !lineFinished) {
                val currentByte = readBuffer[readBufferOffset++]
                lineHasInput = true
                totalResponseBytes++
                if (totalResponseBytes > maximumBytes) {
                    throw ResponseBodyLimitExceededException(maximumBytes)
                }
                if (currentByte == '\n'.code.toByte()) {
                    lineFinished = true
                } else {
                    lineBytes.write(currentByte.toInt())
                }
            }
        }

        if (!lineHasInput) break

        val rawLine = lineBytes.toByteArray()
        // The CR is part of a CRLF delimiter and must not be passed to provider parsers.
        val line = if (lineFinished && rawLine.lastOrNull() == '\r'.code.toByte()) {
            rawLine.copyOf(rawLine.size - 1)
        } else {
            rawLine
        }
        val text = decodeUtf8(line)
        // Only accumulate while the stream is still (potentially) short, so the builder stays bounded
        // for healthy responses. Once past the threshold a line is never part of a suspicious dump.
        if (totalResponseBytes <= 2000) {
            diagnosticText.append(text).append('\n')
        }
        emit(text)
    }

    // Temporary diagnostic: a healthy stream is several KB; below 2000B likely means the provider
    // closed the connection early. Dump the accumulated lines verbatim so they're readable in logs.
    if (totalResponseBytes < 2000) {
        logger.warn("Suspiciously short stream: $totalResponseBytes bytes. Content:\n$diagnosticText")
    }
    logger.debug("Completed reading UTF-8 lines from channel (total bytes read: $totalResponseBytes)")
}

/**
 * Reads a response channel into memory only after enforcing a raw-byte upper bound.
 *
 * The extra byte read when the limit is reached distinguishes an exactly-at-limit body
 * from an oversized body without silently truncating provider data. UTF-8 decoding is
 * intentionally deferred until the bounded read has completed successfully.
 *
 * @param channel The single-reader response channel to consume.
 * @param maximumBytes The inclusive maximum number of raw response bytes to retain.
 * @return The complete response decoded as strict UTF-8.
 * @throws ResponseBodyLimitExceededException If the channel contains more than [maximumBytes] bytes.
 * @throws CharacterCodingException If the bounded bytes are not valid UTF-8.
 * @throws IOException If the response channel closes with an I/O failure.
 */
private suspend fun readUtf8BodyLimited(
    channel: ByteReadChannel,
    maximumBytes: Long
): String {
    require(maximumBytes > 0) { "maximumBytes must be positive" }

    val output = ByteArrayOutputStream(
        minOf(maximumBytes, RESPONSE_READ_BUFFER_SIZE.toLong()).toInt()
    )
    val readBuffer = ByteArray(RESPONSE_READ_BUFFER_SIZE)
    var totalBytes = 0L

    while (true) {
        val remainingBytes = maximumBytes - totalBytes
        val bytesToRead = when {
            remainingBytes >= readBuffer.size.toLong() -> readBuffer.size
            remainingBytes > 0 -> (remainingBytes + 1L).toInt()
            else -> 1
        }
        when (val bytesRead = channel.readAvailable(readBuffer, offset = 0, length = bytesToRead)) {
            -1 -> {
                // A normal EOF has no close cause; a cause must be surfaced as the original
                // I/O or cancellation failure rather than being mistaken for a complete body.
                channel.closedCause?.let { throw it }
                return decodeUtf8(output.toByteArray())
            }

            0 -> continue
            else -> {
                totalBytes += bytesRead
                if (totalBytes > maximumBytes) {
                    throw ResponseBodyLimitExceededException(maximumBytes)
                }
                output.write(readBuffer, 0, bytesRead)
            }
        }
    }
}

/**
 * Decodes a complete provider payload as UTF-8 while rejecting malformed byte sequences.
 *
 * Strict decoding prevents invalid transport data from being silently replaced before a
 * provider strategy attempts to parse it.
 *
 * @param bytes The complete UTF-8 byte sequence to decode.
 * @return The decoded text.
 * @throws CharacterCodingException If [bytes] is not valid UTF-8.
 */
private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

/**
 * Marks an oversized provider response so the transport can abort reading without exposing
 * an implementation-specific exception through the public LLM client API.
 *
 * @property maximumBytes The configured maximum that the response exceeded.
 */
private class ResponseBodyLimitExceededException(
    val maximumBytes: Long
) : IOException("Response exceeded the configured limit of $maximumBytes bytes")

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
