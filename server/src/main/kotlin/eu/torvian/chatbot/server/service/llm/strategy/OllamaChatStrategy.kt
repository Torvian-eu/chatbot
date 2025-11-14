package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig> {
        logger.debug("Preparing request for model ${modelConfig.name}")

        // Ollama typically doesn't require an API key for local instances
        // But it's checked here for completeness

        // 1. Build the messages list with system message if present
        val apiMessages = buildList {
            // Add system message if present in settings
            val systemMessage = settings.systemMessage
            if (!systemMessage.isNullOrBlank()) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(systemMessage))
                })
            }

            // Add all conversation messages
            addAll(messages.map { it.toOllamaApiMessage() })
        }

        // 2. Build options object from settings
        val optionsJson = buildJsonObject {
            // Start with custom parameters from settings
            settings.customParams?.let { params ->
                params.forEach { (key, value) -> put(key, value) }
            }

            // Add/overwrite with specific parameters from ChatModelSettings
            settings.temperature?.let { put("temperature", JsonPrimitive(it)) }
            settings.maxTokens?.let { put("num_predict", JsonPrimitive(it)) }
            settings.topP?.let { put("top_p", JsonPrimitive(it)) }
            settings.stopSequences?.takeIf { it.isNotEmpty() }?.let {
                put("stop", json.encodeToJsonElement(it))
            }
        }

        // 3. Build the request body
        val requestBodyJson = buildJsonObject {
            put("model", JsonPrimitive(modelConfig.name))
            put("messages", JsonArray(apiMessages))
            put("stream", JsonPrimitive(settings.stream))

            // Add options if not empty
            if (optionsJson.isNotEmpty()) {
                put("options", optionsJson)
            }

            // 4. Add tools if provided and not empty
            if (!tools.isNullOrEmpty()) {
                val apiTools = tools.map { mapToolDefinitionToOllamaApi(it) }
                put("tools", json.encodeToJsonElement(apiTools))

                logger.debug("Added ${tools.size} tools to request")
            }
        }

        // 5. Determine API specific path and headers
        val path = "/api/chat"
        val customHeaders = mutableMapOf<String, String>()

        // Add Authorization header if API key is provided
        if (apiKey != null) {
            customHeaders["Authorization"] = "Bearer $apiKey"
        }

        // Pre-serialize the JsonObject
        val requestBodyString = json.encodeToString(JsonObject.serializer(), requestBodyJson)

        logger.debug(
            "Prepared request body: ${requestBodyString.take(500)}..."
        )

        // 6. Return the generic ApiRequestConfig
        return ApiRequestConfig(
            path = path,
            method = GenericHttpMethod.POST,
            body = requestBodyString,
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = customHeaders
        ).right()
    }

    override fun processSuccessResponse(responseBody: String): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult> {
        logger.debug("Processing success response body: ${responseBody.take(500)}...")

        return try {
            // 1. Deserialize the raw string body into the API-specific success response DTO
            val successResponse: OllamaApiModels.ChatCompletionResponse = json.decodeFromString(responseBody)

            // 2. Map tool calls from Ollama response if present
            val toolCalls = successResponse.message.tool_calls?.map { ollamaToolCall ->
                LLMCompletionResult.CompletionChoice.ToolCallRequest(
                    name = ollamaToolCall.function.name,
                    arguments = ollamaToolCall.function.arguments?.toString(),
                    toolCallId = null // Ollama doesn't provide tool call IDs
                )
            }

            // 3. Map to generic LLMCompletionResult
            // Note: Ollama doesn't always provide token usage in non-streaming mode
            val usage = LLMCompletionResult.UsageStats(
                promptTokens = successResponse.prompt_eval_count ?: 0,
                completionTokens = successResponse.eval_count ?: 0,
                totalTokens = (successResponse.prompt_eval_count ?: 0) + (successResponse.eval_count ?: 0)
            )

            // Determine finish reason based on done status
            val finishReason = if (toolCalls != null) {
                "tool_calls"
            } else if (successResponse.done) "stop" else null

            val result = LLMCompletionResult(
                id = null, // Ollama doesn't provide a completion ID
                choices = listOf(
                    // Ollama returns a single message, not a list of choices
                    LLMCompletionResult.CompletionChoice(
                        role = successResponse.message.role,
                        content = successResponse.message.content,
                        finishReason = finishReason,
                        index = 0,
                        toolCalls = toolCalls
                    )
                ),
                usage = usage,
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

            logger.debug("Successfully parsed response")
            result.right()
        } catch (e: Exception) {
            logger.error("Failed to parse success response", e)
            LLMCompletionError.InvalidResponseError(
                "Failed to parse Ollama success response: ${e.message}"
            ).left()
        }
    }

    override fun processStreamingResponse(
        responseStream: Flow<String>
    ): Flow<Either<LLMCompletionError.InvalidResponseError, LLMStreamChunk>> = flow {
        logger.debug("Processing streaming response")

        responseStream.collect { rawChunk ->
            if (rawChunk.isBlank()) {
                // Skip empty lines
                return@collect
            }

            try {
                // Ollama uses NDJSON (newline-delimited JSON) format
                // Each line is a complete JSON object
                val streamChunk: OllamaApiModels.ChatCompletionStreamResponse =
                    json.decodeFromString(rawChunk)

                // Handle the "done" chunk which contains usage statistics
                if (streamChunk.done) {
                    logger.debug("Received done chunk")

                    // Emit usage stats if available
                    if (streamChunk.prompt_eval_count != null && streamChunk.eval_count != null) {
                        emit(
                            LLMStreamChunk.UsageChunk(
                                promptTokens = streamChunk.prompt_eval_count,
                                completionTokens = streamChunk.eval_count,
                                totalTokens = streamChunk.prompt_eval_count + streamChunk.eval_count
                            ).right()
                        )
                    }

                    emit(LLMStreamChunk.Done.right())
                    return@collect
                }

                // Process the message delta if present
                streamChunk.message?.let { message ->
                    // Handle content delta
                    message.content?.let { content ->
                        if (content.isNotEmpty()) {
                            emit(
                                LLMStreamChunk.ContentChunk(
                                    deltaContent = content,
                                    finishReason = null // Ollama sends finish reason in done chunk
                                ).right()
                            )
                        }
                    } ?: run {
                        // This case should ideally not happen for non-done chunks, but for safety
                        logger.warn("Received a non-done chunk without a 'message' field: $rawChunk")
                    }
                }

                // Emit tool calls if available
                streamChunk.message?.tool_calls?.forEach { toolCall ->
                    emit(
                        LLMStreamChunk.ToolCallChunk(
                            index = toolCall.function.index,
                            id = null,
                            name = toolCall.function.name,
                            argumentsDelta = toolCall.function.arguments?.toString()
                        ).right()
                    )
                }
            } catch (e: Exception) {
                logger.error("Error parsing stream chunk: $rawChunk", e)
                emit(
                    LLMCompletionError.InvalidResponseError(
                        "Failed to parse streaming chunk: ${e.message}"
                    ).left()
                )
            }
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("Processing error response body (Status $statusCode): ${errorBody.take(500)}...")
        // 1. Attempt to parse the API-specific error structure from the raw error body
        // Ollama errors typically have a simple {"error": "message"} structure.
        val apiErrorMessage = try {
            val errorResponse = json.decodeFromString<OllamaApiModels.OllamaErrorResponse>(errorBody)
            errorResponse.error
        } catch (e: SerializationException) {
            // If parsing the specific error structure fails, fall back to a generic message
            logger.warn("Failed to parse Ollama specific error body, using raw body.", e)
            errorBody.take(200) // Return start of the raw body if parsing failed
        } catch (e: IllegalArgumentException) {
            logger.warn(
                "The decoded input is not a valid instance of OllamaApiModels.OllamaErrorResponse: $errorBody",
                e
            )
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

    /**
     * Converts a RawChatMessage to Ollama API message format as a JsonObject.
     * Ollama uses a format similar to OpenAI but with some differences:
     * - Tool messages use "name" instead of "tool_call_id"
     * - Tool calls don't have IDs
     *
     * @return JsonObject representing the message in Ollama API format
     */
    private fun RawChatMessage.toOllamaApiMessage(): JsonObject {
        return when (this) {
            is RawChatMessage.User -> buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(content))
            }

            is RawChatMessage.Assistant -> buildJsonObject {
                put("role", JsonPrimitive("assistant"))

                // Content is optional when tool calls are present
                if (content != null) {
                    put("content", JsonPrimitive(content))
                }

                // Add tool calls if present (Ollama format doesn't include IDs)
                val calls = toolCalls
                if (!calls.isNullOrEmpty()) {
                    put("tool_calls", buildJsonArray {
                        calls.forEach { toolCall ->
                            add(buildJsonObject {
                                put("function", buildJsonObject {
                                    put("name", JsonPrimitive(toolCall.name))
                                    // Arguments can be null for parameterless functions
                                    if (toolCall.arguments != null) {
                                        val jsonElement = try {
                                            json.parseToJsonElement(toolCall.arguments)
                                        } catch (_: SerializationException) {
                                            null
                                        }
                                        if (jsonElement != null) {
                                            put("arguments", jsonElement)
                                        }
                                    }
                                })
                            })
                        }
                    })
                }
            }

            is RawChatMessage.Tool -> buildJsonObject {
                put("role", JsonPrimitive("tool"))
                put("content", JsonPrimitive(content))
                // Ollama uses "name" field for tool messages
                put("name", JsonPrimitive(name))
                // Note: Ollama may not support tool_call_id
            }
        }
    }

    /**
     * Converts a domain ToolDefinition to Ollama API ToolDefinition format.
     * Ollama uses the same format as OpenAI for tool definitions.
     *
     * @param tool The domain tool definition
     * @return Ollama API ToolDefinition (which is OpenAI-compatible)
     */
    private fun mapToolDefinitionToOllamaApi(tool: ToolDefinition): OllamaApiModels.ToolDefinition {
        return OllamaApiModels.ToolDefinition(
            type = "function",
            function = OllamaApiModels.ToolDefinition.FunctionDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.inputSchema,
                strict = false
            )
        )
    }
}
