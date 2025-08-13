package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.llm.*
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
        settings: ModelSettings,
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
        val customParams: JsonObject? = settings.customParamsJson?.let { customJson ->
            val element = try {
                json.parseToJsonElement(customJson)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to parse customParamsJson for Ollama request", e)
            }

            // Ensure the parsed element is a JsonObject
            if (element !is JsonObject) {
                throw IllegalStateException("customParamsJson for Ollama request is not a JSON object")
            }
            element
        }

        // Safely extract parameters using JsonObject accessors
        val topP = customParams?.get("top_p")?.jsonPrimitive?.floatOrNull
        val topK = customParams?.get("top_k")?.jsonPrimitive?.intOrNull
        val stop = customParams?.get("stop")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        val seed = customParams?.get("seed")?.jsonPrimitive?.intOrNull

        // Build the Ollama specific options
        val options = OllamaApiModels.ChatCompletionRequest.Options(
            temperature = settings.temperature,
            num_predict = settings.maxTokens,
            top_p = topP,
            top_k = topK,
            stop = stop,
            seed = seed
        )

        // Build the Ollama specific request DTO (@Serializable object)
        val requestBodyDto = OllamaApiModels.ChatCompletionRequest(
            model = modelConfig.name,
            messages = apiMessages,
            stream = false, // We're using non-streaming mode
            options = options
        )

        // 4. Determine API specific path and headers
        val path = "/api/chat"
        val customHeaders = mutableMapOf<String, String>()

        // Ollama typically doesn't require authorization headers for local instances
        // If an API key is provided, we could add it, but it's not standard for Ollama
        logger.debug("OllamaChatStrategy: No authorization header needed for local Ollama instance")

        logger.debug(
            "OllamaChatStrategy: Prepared request body: ${
                json.encodeToString(requestBodyDto).take(500)
            }..."
        ) // Log part of the body

        // 5. Return the generic ApiRequestConfig
        return ApiRequestConfig(
            path = path,
            method = GenericHttpMethod.POST,
            body = requestBodyDto,
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = customHeaders
        ).right()
    }

    override fun processSuccessResponse(responseBody: String): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult> {
        logger.debug("OllamaChatStrategy: Processing success response body: ${responseBody.take(500)}...") // Log part of the body
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
            logger.debug("OllamaChatStrategy: Successfully processed response to LLMCompletionResult")
            result.right()

        } catch (e: Exception) {
            // Handle any exceptions during deserialization or mapping
            logger.error("OllamaChatStrategy: Failed to parse Ollama success response body", e)
            LLMCompletionError.InvalidResponseError("Failed to parse Ollama success response body: ${e.message}", e)
                .left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("OllamaChatStrategy: Processing error response body (Status $statusCode): ${errorBody.take(500)}...")
        // 1. Attempt to parse the API-specific error structure from the raw error body
        // Ollama errors typically have a simple {"error": "message"} structure.
        val apiErrorMessage = try {
            val errorResponse = json.decodeFromString<OllamaApiModels.OllamaErrorResponse>(errorBody)
            errorResponse.error
        } catch (e: Exception) {
            // If parsing the specific error structure fails, fall back to a generic message
            logger.warn("OllamaChatStrategy: Failed to parse Ollama specific error body, using raw body.", e)
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
