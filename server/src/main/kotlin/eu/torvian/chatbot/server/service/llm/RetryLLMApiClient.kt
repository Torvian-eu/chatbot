package eu.torvian.chatbot.server.service.llm

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Decorator that adds retry logic with exponential backoff for LLM requests
 * that receive retryable HTTP status codes (429 Too Many Requests, 503 Service Unavailable).
 *
 * Delegates to an inner [LLMApiClient] and intercepts [LLMCompletionError.ApiError]
 * responses with retryable status codes, retrying the request up to [maxRetries] times
 * with exponential backoff.
 *
 * Retrying works transparently for both streaming and non-streaming requests. For streaming,
 * the entire stream is restarted from scratch if a retryable error occurs before any meaningful
 * content chunks are emitted.
 *
 * @property inner The underlying LLM client to decorate with retry logic.
 * @property maxRetries Maximum number of retries after the initial attempt (must be >= 0).
 * @property baseDelayMs Base delay in milliseconds before the first retry.
 * @property maxDelayMs Maximum delay in milliseconds between retries.
 * @property retryableStatusCodes HTTP status codes that should trigger a retry.
 * @property jitterFactor Factor (0.0–1.0) used to add random jitter to delay intervals,
 *                        preventing thundering-herd retry storms.
 */
class RetryLLMApiClient(
    private val inner: LLMApiClient,
    private val maxRetries: Int = 10,
    private val baseDelayMs: Long = 8_000,
    private val maxDelayMs: Long = 30_000,
    private val retryableStatusCodes: Set<Int> = setOf(429, 503),
    private val jitterFactor: Double = 0.3,
) : LLMApiClient {

    companion object {
        private val logger: Logger = LogManager.getLogger(RetryLLMApiClient::class.java)
    }

    /**
     * Sends a non-streaming chat completion request with retry logic.
     * Retries on 429/503 errors with exponential backoff.
     *
     * [maxRetries] counts retries after the initial attempt, so total attempts are [maxRetries] + 1.
     */
    override suspend fun completeChat(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Either<LLMCompletionError, LLMCompletionResult> {
        for (attempt in 0..maxRetries) {
            currentCoroutineContext().ensureActive()
            when (val result = inner.completeChat(messages, modelConfig, provider, settings, apiKey, tools, systemMessage)) {
                is Either.Right -> return result
                is Either.Left -> {
                    val error = result.value

                    if (!isRetryable(error) || attempt == maxRetries) {
                        return result
                    }

                    val delayMs = calculateBackoff(attempt)
                    val statusCode = (error as LLMCompletionError.ApiError).statusCode
                    logger.warn(
                        "LLM request failed with retryable status $statusCode for provider " +
                        "'${provider.name}' and model '${modelConfig.name}' " +
                        "(attempt ${attempt + 1}/${maxRetries + 1}). Retrying in ${delayMs}ms..."
                    )
                    delay(delayMs.milliseconds)
                }
            }
        }
        // Unreachable: loop always returns on attempt == maxRetries
        throw IllegalStateException("Retry loop exhausted without returning")
    }

    /**
     * Sends a streaming chat completion request with retry logic.
     * Retries on 429/503 errors that occur before any content is streamed, with exponential backoff.
     * If an error occurs after content has been streamed, the error is propagated without retry
     * to avoid duplicate content.
     *
     * [maxRetries] counts retries after the initial attempt, so total attempts are [maxRetries] + 1.
     */
    override fun completeChatStreaming(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
    ): Flow<Either<LLMCompletionError, LLMStreamChunk>> = flow {
        for (attempt in 0..maxRetries) {
            currentCoroutineContext().ensureActive()
            // Request a fresh stream for each attempt.
            var contentEmitted = false
            var retryableErrorEncountered: LLMCompletionError.ApiError? = null

            inner.completeChatStreaming(messages, modelConfig, provider, settings, apiKey, tools, systemMessage)
                .collect { chunkEither ->
                    if (retryableErrorEncountered != null) {
                        // Once a retry decision is made, ignore remaining chunks from this attempt.
                        return@collect
                    }

                    when (chunkEither) {
                        is Either.Right -> {
                            val chunk = chunkEither.value
                            // Content/tool-call chunks are externally visible and must not be duplicated on retry.
                            if (chunk is LLMStreamChunk.ContentChunk || chunk is LLMStreamChunk.ToolCallChunk) {
                                contentEmitted = true
                            }

                            // A provider can report a failure inside an otherwise-successful stream as an
                            // Error chunk (e.g. OpenRouter embeds an ApiError in a "data:" line that is not
                            // part of the choices). Treat a retryable ApiError that arrives before any content
                            // as retryable, restarting the stream from scratch, mirroring the Either.Left path.
                            if (chunk is LLMStreamChunk.Error) {
                                val llmError = chunk.llmError
                                val shouldRetry = isRetryable(llmError) && !contentEmitted && attempt < maxRetries
                                if (shouldRetry) {
                                    retryableErrorEncountered = llmError as LLMCompletionError.ApiError
                                    return@collect
                                }
                            }

                            emit(chunkEither)
                        }

                        is Either.Left -> {
                            val error = chunkEither.value
                            val shouldRetry = isRetryable(error) && !contentEmitted && attempt < maxRetries
                            if (shouldRetry) {
                                retryableErrorEncountered = error as LLMCompletionError.ApiError
                            } else {
                                emit(chunkEither)
                            }
                        }
                    }
                }

            val retryableError = retryableErrorEncountered
            if (retryableError != null) {
                val delayMs = calculateBackoff(attempt)
                logger.warn(
                    "LLM streaming request failed with retryable status ${retryableError.statusCode} for provider " +
                    "'${provider.name}' and model '${modelConfig.name}' before any content was emitted " +
                    "(attempt ${attempt + 1}/${maxRetries + 1}). Retrying in ${delayMs}ms..."
                )
                delay(delayMs.milliseconds)
                continue
            }

            // Stream completed successfully or emitted a terminal non-retry path.
            return@flow
        }
    }

    /**
     * Discovers available remote models for the given provider.
     * Model discovery errors are not retried — they are typically configuration issues.
     */
    override suspend fun discoverModels(
        provider: LLMProvider,
        apiKey: String?
    ): Either<ModelDiscoveryError, ModelDiscoveryResult> {
        return inner.discoverModels(provider, apiKey)
    }

    /**
     * Checks whether an [LLMCompletionError] is retryable.
     * Only [LLMCompletionError.ApiError] with status codes in [retryableStatusCodes] are retryable.
     */
    private fun isRetryable(error: LLMCompletionError): Boolean {
        return when (error) {
            is LLMCompletionError.ApiError -> error.statusCode in retryableStatusCodes
            else -> false
        }
    }

    /**
     * Calculates the backoff delay for a given retry attempt using exponential backoff
     * with optional random jitter.
     *
     * Formula: min(baseDelay * 2^attempt, maxDelay) + random(0, delay * jitterFactor)
     *
     * @param attempt The current retry attempt (0-based, where 0 is the first retry after the initial attempt).
     * @return The delay in milliseconds before the next retry attempt.
     */
    private fun calculateBackoff(attempt: Int): Long {
        // Calculate exponential delay: baseDelay * 2^attempt
        val exponentialDelay = baseDelayMs * (1L shl attempt)
        // Cap the delay at maxDelayMs
        val cappedDelay = minOf(exponentialDelay, maxDelayMs)
        // Add jitter: a random amount up to jitterFactor * cappedDelay
        val jitterAmount = (cappedDelay * jitterFactor * Random.nextDouble()).toLong()
        return cappedDelay + jitterAmount
    }
}

