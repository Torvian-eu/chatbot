package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.llm.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Chat completion strategy for Ollama API.
 * Handles mapping generic ChatMessage/ModelSettings to Ollama API request format,
 * and mapping Ollama API response format to generic LLMCompletionResult.
 * Depends on kotlinx.serialization.Json to parse raw response strings.
 *
 * @property json The Json instance used for serialization/deserialization
 */
class OllamaChatStrategy(private val json: Json) : ChatCompletionStrategy {

    private val logger: Logger = LogManager.getLogger(OllamaChatStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OLLAMA

    override fun prepareRequest(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig> {
        logger.debug("OllamaChatStrategy: Preparing request for model ${modelConfig.name}")

        // 1. Ollama typically doesn't require an API key for local instances
        // However, we don't validate against apiKey being provided as some setups might use it
        logger.debug("OllamaChatStrategy: API key requirement check - Ollama typically runs locally without API key")

        // 2. Map generic ChatMessage list to Ollama API specific RequestMessage list
        fun ChatMessage.toOllamaApiMessage(): OllamaApiModels.ChatCompletionRequest.RequestMessage {
            return OllamaApiModels.ChatCompletionRequest.RequestMessage(
                role = when (this.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                },
                content = this.content
            )
        }

        // Add system message if present in settings
        val systemMessage = settings.systemMessage
        val apiMessages = buildList {
            if (!systemMessage.isNullOrBlank()) {
                add(OllamaApiModels.ChatCompletionRequest.RequestMessage(role = "system", content = systemMessage))
            }
            // Add user and assistant messages from the conversation history
            addAll(messages.map { it.toOllamaApiMessage() })
        }

        // 3. Map ModelSettings to Ollama API request parameters
        // Build the nested 'options' object first.
        // Start with customParams, then overwrite with specific settings for precedence.
        val optionsJson = buildJsonObject {
            settings.customParams?.let { params ->
                params.forEach { (key, value) -> put(key, value) }
            }

            // Add/overwrite with specific parameters from ChatModelSettings
            settings.temperature?.let { put("temperature", JsonPrimitive(it)) }
            settings.maxTokens?.let { put("num_predict", JsonPrimitive(it)) } // Mapping maxTokens to num_predict
            settings.topP?.let { put("top_p", JsonPrimitive(it)) }
            settings.stopSequences?.takeIf { it.isNotEmpty() }?.let {
                put("stop", json.encodeToJsonElement(it))
            }
        }

        // Build the main request body as a flexible JsonObject.
        val requestBodyJson = buildJsonObject {
            put("model", JsonPrimitive(modelConfig.name))
            put("messages", json.encodeToJsonElement(apiMessages))
            put("stream", JsonPrimitive(settings.stream))
            if (optionsJson.isNotEmpty()) {
                put("options", optionsJson)
            }
        }

        // 4. Determine API specific path and headers
        val path = "/api/chat"
        val customHeaders = mutableMapOf<String, String>()

        // Ollama typically doesn't require authorization headers for local instances
        logger.debug("OllamaChatStrategy: No authorization header needed for local Ollama instance")

        val requestBodyString = json.encodeToString(requestBodyJson)
        logger.debug(
            "OllamaChatStrategy: Prepared request body: ${
                requestBodyString.take(500)
            }..."
        ) // Log part of the body

        // 5. Return the generic ApiRequestConfig
        return ApiRequestConfig(
            path = path,
            method = GenericHttpMethod.POST,
            body = requestBodyString, // Pass the pre-serialized JSON string
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = customHeaders
        ).right()
    }

    override fun processSuccessResponse(responseBody: String): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult> {
        // This method is for *non-streaming* responses, typically used by completeChat.
        logger.debug("OllamaChatStrategy: Processing success response body (non-streaming): ${responseBody.take(500)}...")
        return try {
            // 1. Deserialize the raw string body into the API-specific success response DTO
            val successResponse: OllamaApiModels.ChatCompletionResponse = json.decodeFromString(responseBody)

            // 2. Map the API-specific DTO to the generic LLMCompletionResult
            val result = LLMCompletionResult(
                id = null, // Ollama doesn't provide a completion ID in the same way as OpenAI
                choices = listOf(
                    // Ollama returns a single message, not a list of choices
                    LLMCompletionResult.CompletionChoice(
                        role = successResponse.message.role,
                        content = successResponse.message.content,
                        finishReason = if (successResponse.done) "stop" else null,
                        index = 0
                    )
                ),
                usage = LLMCompletionResult.UsageStats(
                    promptTokens = successResponse.prompt_eval_count ?: 0,
                    completionTokens = successResponse.eval_count ?: 0,
                    totalTokens = (successResponse.prompt_eval_count ?: 0) + (successResponse.eval_count ?: 0)
                ),
                metadata = mapOf(
                    "api_model" to successResponse.model,
                    "api_created_at" to successResponse.created_at,
                    "api_done" to successResponse.done,
                    "total_duration" to successResponse.total_duration,
                    "load_duration" to successResponse.load_duration,
                    "prompt_eval_duration" to successResponse.prompt_eval_duration,
                    "eval_duration" to successResponse.eval_duration
                ).filterValues { it != null }
            )
            logger.debug("OllamaChatStrategy: Successfully processed non-streaming response to LLMCompletionResult")
            result.right()

        } catch (e: SerializationException) {
            logger.error("OllamaChatStrategy: Failed to parse Ollama non-streaming success response body", e)
            LLMCompletionError.InvalidResponseError("Failed to parse Ollama non-streaming success response body: ${e.message}", e).left()
        } catch (e: IllegalArgumentException) {
            logger.error("OllamaChatStrategy: The decoded input is not a valid instance of OllamaApiModels.ChatCompletionResponse: $responseBody", e)
            LLMCompletionError.InvalidResponseError("The decoded input is not a valid instance of OllamaApiModels.ChatCompletionResponse: ${e.message}", e).left()
        }
    }

    override fun processStreamingResponse(
        responseStream: Flow<String>
    ): Flow<Either<LLMCompletionError.InvalidResponseError, LLMStreamChunk>> = flow {
        responseStream.collect { rawChunk ->
            if (rawChunk.isBlank()) return@collect // Ignore empty lines

            try {
                // Ollama sends newline-delimited JSON objects
                val streamResponse = json.decodeFromString<OllamaApiModels.ChatCompletionStreamResponse>(rawChunk)

                if (streamResponse.done) {
                    // This is the final chunk, might contain usage stats
                    val usage = LLMCompletionResult.UsageStats(
                        promptTokens = streamResponse.prompt_eval_count ?: 0,
                        completionTokens = streamResponse.eval_count ?: 0,
                        totalTokens = (streamResponse.prompt_eval_count ?: 0) + (streamResponse.eval_count ?: 0)
                    )
                    emit(
                        LLMStreamChunk.UsageChunk(usage.promptTokens, usage.completionTokens, usage.totalTokens).right()
                    )
                    emit(LLMStreamChunk.Done.right())
                } else {
                    // Regular content chunk
                    streamResponse.message?.let { message ->
                        emit(LLMStreamChunk.ContentChunk(message.content).right())
                    } ?: run {
                        // This case should ideally not happen for non-done chunks, but for safety
                        logger.warn("OllamaChatStrategy: Received a non-done chunk without a 'message' field: $rawChunk")
                    }
                }
            } catch (e: SerializationException) {
                logger.error("OllamaChatStrategy: Failed to parse Ollama streaming response chunk: $rawChunk", e)
                emit(LLMCompletionError.InvalidResponseError("Failed to parse Ollama streaming response chunk: ${e.message}", e).left())
            } catch (e: IllegalArgumentException) {
                logger.error("OllamaChatStrategy: The decoded input is not a valid instance of OllamaApiModels.ChatCompletionStreamResponse: $rawChunk", e)
                emit(LLMCompletionError.InvalidResponseError("The decoded input is not a valid instance of OllamaApiModels.ChatCompletionStreamResponse: ${e.message}", e).left())
            }
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("OllamaChatStrategy: Processing error response body (Status $statusCode): ${errorBody.take(500)}...")
        // 1. Attempt to parse the API-specific error structure from the raw error body
        // Ollama errors typically have a simple {"error": "message"} structure.
        val apiErrorMessage = try {
            val errorResponse = json.decodeFromString<OllamaApiModels.OllamaErrorResponse>(errorBody)
            errorResponse.error
        } catch (e: SerializationException) {
            // If parsing the specific error structure fails, fall back to a generic message
            logger.warn("OllamaChatStrategy: Failed to parse Ollama specific error body, using raw body.", e)
            errorBody.take(200) // Return start of the raw body if parsing failed
        } catch (e: IllegalArgumentException) {
            logger.warn("OllamaChatStrategy: The decoded input is not a valid instance of OllamaApiModels.OllamaErrorResponse: $errorBody", e)
            errorBody.take(200) // Return start of the raw body if parsing failed
        }

        // 2. Map the status code and extracted message to a specific generic LLMCompletionError type
        return when (statusCode) {
            400 -> LLMCompletionError.ApiError(
                statusCode,
                "Ollama API bad request: $apiErrorMessage",
                errorBody
            )
            404 -> LLMCompletionError.ApiError(
                statusCode,
                "Ollama API model or endpoint not found: $apiErrorMessage",
                errorBody
            )
            500 -> LLMCompletionError.ApiError(
                statusCode,
                "Ollama API internal server error: $apiErrorMessage",
                errorBody
            )
            // Add other known Ollama specific status codes/error types if needed
            else -> LLMCompletionError.ApiError(
                statusCode,
                "Ollama API returned error $statusCode: $apiErrorMessage",
                errorBody
            )
        }
    }
}
