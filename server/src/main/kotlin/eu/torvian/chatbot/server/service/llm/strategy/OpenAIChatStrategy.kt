package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.llm.*
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Chat completion strategy for OpenAI compatible APIs.
 * Handles mapping generic ChatMessage/ModelSettings to OpenAI API request format,
 * and mapping OpenAI API response format to generic LLMCompletionResult.
 * Depends on kotlinx.serialization.Json to parse raw response strings.
 * 
 * @property json The Json instance used for serialization/deserialization
 */
class OpenAIChatStrategy(private val json: Json) : ChatCompletionStrategy { // Inject Json instance

    private val logger: Logger = LogManager.getLogger(OpenAIChatStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OPENAI

    override fun prepareRequest(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider, // Still useful for base URL verification, type check
        settings: ModelSettings,
        apiKey: String?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig> {

        logger.debug("OpenAIChatStrategy: Preparing request for model ${modelConfig.name}")

        // 1. Perform validation specific to OpenAI (e.g., requires apiKey)
        if (provider.apiKeyId != null && apiKey == null) {
            return LLMCompletionError.ConfigurationError("OpenAI provider '${provider.name}' requires an API key, but none was provided.").left()
        }
        // Note: apiKey being null might be valid if provider.apiKeyId is null (e.g., self-hosted OpenAI API compatible)
        // Consider if a null apiKey should always be an error if apiKeyId is NOT null.
        // The MessageServiceImpl checks apiKeyId != null AND apiKey == null, which is more accurate.

        // 2. Map generic ChatMessage list to OpenAI API specific RequestMessage list
        fun ChatMessage.toOpenAiApiMessage(): OpenAiApiModels.ChatCompletionRequest.RequestMessage {
             // Ensure roles are mapped correctly to OpenAI's expected string values
             return OpenAiApiModels.ChatCompletionRequest.RequestMessage(
                 role = when(this.role) {
                     ChatMessage.Role.USER -> "user"
                     ChatMessage.Role.ASSISTANT -> "assistant"
                 },
                 content = this.content
             )
        }

        val apiMessages = messages.map { it.toOpenAiApiMessage() }

        // 3. Map ModelSettings to OpenAI API request parameters
        // Parse customParamsJson for additional OpenAI-specific parameters
        val customParams = settings.customParamsJson?.let { customJson ->
            try {
                json.decodeFromString<Map<String, Any?>>(customJson)
            } catch (e: Exception) {
                logger.warn("Failed to parse customParamsJson for OpenAI request: ${e.message}")
                emptyMap<String, Any?>()
            }
        } ?: emptyMap()

        // Build the OpenAI specific request DTO (@Serializable object)
        val requestBodyDto = OpenAiApiModels.ChatCompletionRequest(
            model = modelConfig.name, // Use the model's name string which is the API identifier
            messages = apiMessages,
            temperature = settings.temperature,
            max_tokens = settings.maxTokens,
            top_p = customParams["top_p"] as? Float,
            frequency_penalty = customParams["frequency_penalty"] as? Float,
            presence_penalty = customParams["presence_penalty"] as? Float,
            stop = (customParams["stop"] as? List<*>)?.mapNotNull { it as? String }
            // Additional OpenAI parameters can be added here as needed
            // E.g., seed = customParams["seed"] as? Int
        )

        // 4. Determine API specific path and headers
        val path = "/chat/completions"
        val customHeaders = mutableMapOf<String, String>()

        // Add Authorization header ONLY if API key is required AND provided
        if (apiKey != null) { // Check apiKey is NOT null before adding header
            // OpenAI uses "Bearer" token authentication
            customHeaders[HttpHeaders.Authorization] = "Bearer $apiKey"
        }

        logger.debug("OpenAIChatStrategy: Prepared request body: ${json.encodeToString(requestBodyDto).take(500)}...") // Log part of the body

        // 5. Return the generic ApiRequestConfig
        return ApiRequestConfig(
            path = path,
            method = GenericHttpMethod.POST,
            body = requestBodyDto, // Pass the @Serializable DTO object
            contentType = GenericContentType.APPLICATION_JSON, // Specify content type
            customHeaders = customHeaders
        ).right() // Return Right(config)
    }

    override fun processSuccessResponse(responseBody: String): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult> {
        logger.debug("OpenAIChatStrategy: Processing success response body: ${responseBody.take(500)}...") // Log part of the body
        return try {
            // 1. Deserialize the raw string body into the API-specific success response DTO
            val successResponse: OpenAiApiModels.ChatCompletionResponse = json.decodeFromString(responseBody)

            // 2. Map the API-specific DTO to the generic LLMCompletionResult
            val result = LLMCompletionResult(
                id = successResponse.id,
                choices = successResponse.choices.map { choice ->
                    // Map each API choice to a generic CompletionChoice
                    LLMCompletionResult.CompletionChoice(
                        role = choice.message.role, // Use the string role from the API response
                        content = choice.message.content,
                        finishReason = choice.finish_reason,
                        index = choice.index
                    )
                },
                usage = LLMCompletionResult.UsageStats(
                    promptTokens = successResponse.usage.prompt_tokens,
                    completionTokens = successResponse.usage.completion_tokens,
                    totalTokens = successResponse.usage.total_tokens
                ),
                metadata = mapOf( // Include some relevant API-specific metadata in the result
                    "api_object" to successResponse.`object`,
                    "api_created" to successResponse.created,
                    "api_model" to successResponse.model
                )
            )
            logger.debug("OpenAIChatStrategy: Successfully processed response to LLMCompletionResult")
            result.right() // Return Right(result)

        } catch (e: Exception) {
            // Handle any exceptions during deserialization or mapping
            logger.error("OpenAIChatStrategy: Failed to parse OpenAI success response body", e)
            LLMCompletionError.InvalidResponseError("Failed to parse OpenAI success response body: ${e.message}", e).left() // Return Left(error)
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("OpenAIChatStrategy: Processing error response body (Status $statusCode): ${errorBody.take(500)}...") // Log part of the body
        // 1. Attempt to parse the API-specific error structure from the raw error body
        // OpenAI errors typically have a specific {"error": {...}} structure.
        val apiErrorMessage = try {
             val errorResponse = json.decodeFromString<OpenAiApiModels.OpenAiErrorResponse>(errorBody)
             // Use the message from the specific error structure
             errorResponse.error.message
        } catch (e: Exception) {
            // If parsing the specific error structure fails, fall back to a generic message
            logger.warn("OpenAIChatStrategy: Failed to parse OpenAI specific error body, using raw body.", e)
            errorBody.take(200) // Return start of the raw body if parsing failed
        }

        // 2. Map the status code and extracted message to a specific generic LLMCompletionError type
        return when (statusCode) {
            401, 403 -> LLMCompletionError.AuthenticationError("OpenAI API authentication failed: $apiErrorMessage")
            404 -> LLMCompletionError.ApiError(statusCode, "OpenAI API endpoint or model not found: $apiErrorMessage", errorBody)
            429 -> LLMCompletionError.ApiError(statusCode, "OpenAI API rate limit exceeded: $apiErrorMessage", errorBody)
            // Add other known OpenAI specific status codes/error types if needed
            else -> LLMCompletionError.ApiError(statusCode, "OpenAI API returned error $statusCode: $apiErrorMessage", errorBody)
        }
    }
}
