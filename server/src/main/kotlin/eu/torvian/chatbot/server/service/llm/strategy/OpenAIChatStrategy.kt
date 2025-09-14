package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.service.llm.*
import io.ktor.http.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
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
class OpenAIChatStrategy(private val json: Json) : ChatCompletionStrategy {

    private val logger: Logger = LogManager.getLogger(OpenAIChatStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OPENAI

    override fun prepareRequest(
        messages: List<ChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig> {

        logger.debug("OpenAIChatStrategy: Preparing request for model ${modelConfig.name}")

        // 1. Perform validation specific to OpenAI (e.g., requires apiKey)
        if (provider.apiKeyId != null && apiKey == null) {
            return LLMCompletionError.ConfigurationError("OpenAI provider '${provider.name}' requires an API key, but none was provided.")
                .left()
        }
        // Note: apiKey being null might be valid if provider.apiKeyId is null (e.g., self-hosted OpenAI API compatible)
        // Consider if a null apiKey should always be an error if apiKeyId is NOT null.
        // The MessageServiceImpl checks apiKeyId != null AND apiKey == null, which is more accurate.

        // 2. Map generic ChatMessage list to OpenAI API specific RequestMessage list
        fun ChatMessage.toOpenAiApiMessage(): OpenAiApiModels.ChatCompletionRequest.RequestMessage {
            // Ensure roles are mapped correctly to OpenAI's expected string values
            return OpenAiApiModels.ChatCompletionRequest.RequestMessage(
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
                add(OpenAiApiModels.ChatCompletionRequest.RequestMessage(role = "system", content = systemMessage))
            }
            // Add user and assistant messages from the conversation history
            addAll(messages.map { it.toOpenAiApiMessage() })
        }

        // 3. Build the request body as a flexible JsonObject.
        // This allows passing any parameter from customParams to the OpenAI API.
        // Specific settings (e.g., temperature) will override any values from customParams.
        val requestBodyJson = buildJsonObject {
            // Start with custom parameters from settings.
            settings.customParams?.let { params ->
                params.forEach { (key, value) -> put(key, value) }
            }

            // Add/overwrite with standard and required parameters.
            put("model", JsonPrimitive(modelConfig.name))
            put("messages", json.encodeToJsonElement(apiMessages))
            put("stream", JsonPrimitive(settings.stream))

            // Add/overwrite with specific parameters from ChatModelSettings
            settings.temperature?.let { put("temperature", JsonPrimitive(it)) }
            settings.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            settings.topP?.let { put("top_p", JsonPrimitive(it)) }
            settings.stopSequences?.takeIf { it.isNotEmpty() }?.let {
                put("stop", json.encodeToJsonElement(it))
            }
        }

        // 4. Determine API specific path and headers
        val path = "/chat/completions"
        val customHeaders = mutableMapOf<String, String>()

        // Add Authorization header ONLY if API key is required AND provided
        if (apiKey != null) {
            customHeaders[HttpHeaders.Authorization] = "Bearer $apiKey"
        }

        // Pre-serialize the JsonObject to avoid Ktor serialization issues
        val requestBodyString = json.encodeToString(requestBodyJson)

        logger.debug(
            "OpenAIChatStrategy: Prepared request body: ${
                requestBodyString.take(500)
            }..."
        ) // Log part of the body

        // 5. Return the generic ApiRequestConfig
        return ApiRequestConfig(
            path = path,
            method = GenericHttpMethod.POST,
            body = requestBodyString,
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = customHeaders
        ).right()
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
                        role = choice.message.role,
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
                metadata = mapOf(
                    "api_object" to successResponse.`object`,
                    "api_created" to successResponse.created,
                    "api_model" to successResponse.model
                )
            )
            logger.debug("OpenAIChatStrategy: Successfully processed response to LLMCompletionResult")
            result.right()

        } catch (e: Exception) {
            // Handle any exceptions during deserialization or mapping
            logger.error("OpenAIChatStrategy: Failed to parse OpenAI success response body", e)
            LLMCompletionError.InvalidResponseError("Failed to parse OpenAI success response body: ${e.message}", e)
                .left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("OpenAIChatStrategy: Processing error response body (Status $statusCode): ${errorBody.take(500)}...")
        // 1. Attempt to parse the API-specific error structure from the raw error body
        // OpenAI errors typically have a specific {"error": {...}} structure.
        val apiErrorMessage = try {
            val errorResponse = json.decodeFromString<OpenAiApiModels.OpenAiErrorResponse>(errorBody)
            errorResponse.error.message
        } catch (e: Exception) {
            // If parsing the specific error structure fails, fall back to a generic message
            logger.warn("OpenAIChatStrategy: Failed to parse OpenAI specific error body, using raw body.", e)
            errorBody.take(200) // Return start of the raw body if parsing failed
        }

        // 2. Map the status code and extracted message to a specific generic LLMCompletionError type
        return when (statusCode) {
            401, 403 -> LLMCompletionError.AuthenticationError("OpenAI API authentication failed: $apiErrorMessage")
            404 -> LLMCompletionError.ApiError(
                statusCode,
                "OpenAI API endpoint or model not found: $apiErrorMessage",
                errorBody
            )

            429 -> LLMCompletionError.ApiError(
                statusCode,
                "OpenAI API rate limit exceeded: $apiErrorMessage",
                errorBody
            )
            // Add other known OpenAI specific status codes/error types if needed
            else -> LLMCompletionError.ApiError(
                statusCode,
                "OpenAI API returned error $statusCode: $apiErrorMessage",
                errorBody
            )
        }
    }

    override fun processStreamingResponse(
        responseStream: Flow<String>
    ): Flow<Either<LLMCompletionError.InvalidResponseError, LLMStreamChunk>> = flow {
        // TODO: Implement OpenAI streaming response processing
        // OpenAI uses Server-Sent Events (SSE) format for streaming
        // This is a placeholder implementation
        emit(LLMCompletionError.InvalidResponseError("OpenAI streaming not yet implemented", null).left())
    }
}
