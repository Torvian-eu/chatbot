package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ChatModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig> {

        logger.debug("Preparing request for model ${modelConfig.name}")

        // 1. Perform validation specific to OpenAI (e.g., requires apiKey)
        if (provider.apiKeyId != null && apiKey == null) {
            return LLMCompletionError.ConfigurationError(
                "OpenAI provider '${provider.name}' requires an API key, but none was provided."
            ).left()
        }

        // 2. Build the messages list with system message if present
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
            addAll(messages.map { it.toOpenAiApiMessage() })
        }

        // 3. Build the request body as a flexible JsonObject
        val requestBodyJson = buildJsonObject {
            // Start with custom parameters from settings
            settings.customParams?.let { params ->
                params.forEach { (key, value) -> put(key, value) }
            }

            // Add/overwrite with standard and required parameters
            put("model", JsonPrimitive(modelConfig.name))
            put("messages", JsonArray(apiMessages))
            put("stream", JsonPrimitive(settings.stream))

            // Add stream_options when streaming is enabled to request usage statistics
            if (settings.stream) {
                put("stream_options", buildJsonObject {
                    put("include_usage", JsonPrimitive(true))
                })
            }

            // Add/overwrite with specific parameters from ChatModelSettings
            settings.temperature?.let { put("temperature", JsonPrimitive(it)) }
            settings.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            settings.topP?.let { put("top_p", JsonPrimitive(it)) }
            settings.stopSequences?.takeIf { it.isNotEmpty() }?.let {
                put("stop", json.encodeToJsonElement(it))
            }

            // 4. Add tools if provided and not empty
            if (!tools.isNullOrEmpty()) {
                val apiTools = tools.map { mapToolDefinitionToOpenAiApi(it) }
                put("tools", json.encodeToJsonElement(apiTools))
                // Set tool_choice to "auto" to let the model decide when to use tools
                put("tool_choice", JsonPrimitive("auto"))

                logger.debug("Added ${tools.size} tools to request")
            }
        }

        // 5. Determine API specific path and headers
        val path = "/chat/completions"
        val customHeaders = mutableMapOf<String, String>()

        // Add Authorization header ONLY if API key is required AND provided
        if (apiKey != null) {
            customHeaders[HttpHeaders.Authorization] = "Bearer $apiKey"
        }

        // Pre-serialize the JsonObject to avoid Ktor serialization issues
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
            val successResponse: OpenAiApiModels.ChatCompletionResponse = json.decodeFromString(responseBody)

            // 2. Map the API-specific DTO to the generic LLMCompletionResult
            val result = LLMCompletionResult(
                id = successResponse.id,
                choices = successResponse.choices.map { choice ->
                    // Map tool calls from API response if present
                    val toolCalls = choice.message.tool_calls?.map { apiToolCall ->
                        LLMCompletionResult.CompletionChoice.ToolCallRequest(
                            name = apiToolCall.function.name,
                            arguments = apiToolCall.function.arguments,
                            toolCallId = apiToolCall.id
                        )
                    }

                    // Map each API choice to a generic CompletionChoice
                    LLMCompletionResult.CompletionChoice(
                        role = choice.message.role,
                        content = choice.message.content,
                        finishReason = choice.finish_reason,
                        index = choice.index,
                        toolCalls = toolCalls
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
            logger.debug("Successfully parsed response with ${result.choices.size} choice(s)")
            result.right()

        } catch (e: Exception) {
            // Handle any exceptions during deserialization or mapping
            logger.error("Failed to parse OpenAI success response body", e)
            LLMCompletionError.InvalidResponseError("Failed to parse OpenAI success response body: ${e.message}", e)
                .left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("Processing error response body (Status $statusCode): ${errorBody.take(500)}...")
        // 1. Attempt to parse the API-specific error structure from the raw error body
        // Gemini error responses are wrapped in an array. Needs to be unwrapped first.
        val errorBodyUnwrapped = if (errorBody.startsWith("[")) {
            errorBody.substring(1, errorBody.length - 1)
        } else {
            errorBody
        }
        val apiErrorMessage = try {
            val errorResponse = json.decodeFromString<OpenAiApiModels.OpenAiErrorResponse>(errorBodyUnwrapped)
            errorResponse.error.message
        } catch (e: Exception) {
            // If parsing the specific error structure fails, fall back to a generic message
            logger.warn("Failed to parse OpenAI specific error body, using raw body.", e)
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
        logger.debug("Processing streaming response")

        responseStream.collect { rawChunk ->
            try {
                // OpenAI uses Server-Sent Events (SSE) format
                // Each line starts with "data: " followed by JSON
                // The stream ends with "data: [DONE]"

                if (rawChunk.isBlank()) {
                    // Skip empty lines
                    return@collect
                }

                if (!rawChunk.startsWith("data: ")) {
                    // Skip lines that don't match SSE format
                    logger.trace("Skipping non-data line: $rawChunk")
                    return@collect
                }

                val dataContent = rawChunk.removePrefix("data: ").trim()

                if (dataContent == "[DONE]") {
                    // Stream end marker
                    logger.debug("Received [DONE] marker")
                    return@collect
                }

                // Parse the JSON chunk
                val streamChunk: OpenAiApiModels.ChatCompletionStreamChunk =
                    json.decodeFromString(dataContent)

                // Process usage information if present
                streamChunk.usage?.let { usage ->
                    emit(
                        LLMStreamChunk.UsageChunk(
                            promptTokens = usage.prompt_tokens,
                            completionTokens = usage.completion_tokens,
                            totalTokens = usage.total_tokens
                        ).right()
                    )
                }

                // Process each choice in the chunk
                streamChunk.choices.forEach { choice ->
                    // 1. Handle tool call deltas
                    var toolCallChunks = choice.delta.tool_calls?.map { toolCallDelta ->
                        LLMStreamChunk.ToolCallChunk(
                            index = toolCallDelta.index,
                            id = toolCallDelta.id,
                            name = toolCallDelta.function.name,
                            argumentsDelta = toolCallDelta.function.arguments
                        )
                    }
                    if (toolCallChunks != null) {
                        // If multiple tool-call chunks exist but none have an index, assign them sequential indices starting at 0
                        // This is a fallback for APIs that don't provide indices (e.g. Gemini in OpenAI mode)
                        // (For Gemini, the tool calls are not sent in chunks, but rather all at once.)
                        if (toolCallChunks.size > 1 && toolCallChunks.all { it.index == null }) {
                            toolCallChunks = toolCallChunks.mapIndexed { index, chunk ->
                                chunk.copy(index = index)
                            }
                        }
                        // Emit tool call chunks
                        toolCallChunks.forEach {
                            emit(it.right())
                        }
                    }

                    // 2. Handle content deltas
                    choice.delta.content?.let { content ->
                        if (content.isNotEmpty()) {
                            emit(
                                LLMStreamChunk.ContentChunk(
                                    deltaContent = content,
                                    finishReason = choice.finish_reason
                                ).right()
                            )
                        }
                    }

                    // 3. If finish_reason is present without content, emit a chunk for it
                    if (choice.finish_reason != null && choice.delta.content == null) {
                        emit(
                            LLMStreamChunk.ContentChunk(
                                deltaContent = "",
                                finishReason = choice.finish_reason
                            ).right()
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to parse OpenAI streaming JSON chunk: $rawChunk", e)
                emit(
                    LLMCompletionError.InvalidResponseError(
                        "Failed to parse OpenAI streaming JSON chunk: ${e.message}"
                    ).left()
                )
            }
        }
        // After the stream has been fully collected (usually after the [DONE] signal was received),
        // emit the final Done chunk to signal completion to the consumer.
        emit(LLMStreamChunk.Done.right())
    }

    /**
     * Converts a RawChatMessage to an OpenAI API message format as a JsonObject.
     * This function handles User, Assistant, and Tool message types.
     *
     * Note: We build JsonObject directly rather than using RequestMessage DTO
     * because the OpenAI message format is polymorphic and complex, especially
     * for assistant messages with tool calls and tool result messages.
     *
     * @return JsonObject representing the message in OpenAI API format
     */
    private fun RawChatMessage.toOpenAiApiMessage(): JsonObject {
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

                // Add tool calls if present
                val calls = toolCalls
                if (!calls.isNullOrEmpty()) {
                    put("tool_calls", buildJsonArray {
                        calls.forEach { toolCall ->
                            add(buildJsonObject {
                                // id is required by OpenAI
                                put("id", JsonPrimitive(toolCall.id ?: ""))
                                put("type", JsonPrimitive("function"))
                                put("function", buildJsonObject {
                                    put("name", JsonPrimitive(toolCall.name))
                                    // Arguments can be null for parameterless functions
                                    if (toolCall.arguments != null) {
                                        put("arguments", JsonPrimitive(toolCall.arguments))
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
                put("tool_call_id", JsonPrimitive(toolCallId))
                // OpenAI doesn't require 'name' field for tool messages, but we can include it for clarity
                // put("name", JsonPrimitive(name))
            }
        }
    }

    /**
     * Converts a domain ToolDefinition to OpenAI API ToolDefinition format.
     *
     * The domain ToolDefinition contains all tool information including configuration,
     * but the OpenAI API only needs the function name, description, and parameters schema.
     *
     * @param tool The domain tool definition
     * @return OpenAI API ToolDefinition
     */
    private fun mapToolDefinitionToOpenAiApi(tool: ToolDefinition): OpenAiApiModels.ToolDefinition {
        return OpenAiApiModels.ToolDefinition(
            type = "function",
            function = OpenAiApiModels.ToolDefinition.FunctionDefinition(
                name = tool.name,
                description = tool.description,
                parameters = tool.inputSchema,
                strict = false // Can be made configurable via tool.config if needed
            )
        )
    }
}
