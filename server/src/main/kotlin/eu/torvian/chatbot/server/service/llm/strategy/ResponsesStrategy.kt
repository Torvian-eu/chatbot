package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.ApiRequestConfig
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategy
import eu.torvian.chatbot.server.service.llm.GenericContentType
import eu.torvian.chatbot.server.service.llm.GenericHttpMethod
import eu.torvian.chatbot.server.service.llm.LLMCompletionError
import eu.torvian.chatbot.server.service.llm.LLMCompletionResult
import eu.torvian.chatbot.server.service.llm.LLMStreamChunk
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Chat completion strategy for OpenAI's Responses API (`POST /v1/responses`).
 *
 * Unlike the Chat Completions dialect handled by [OpenAIChatStrategy], the Responses API uses a
 * top-level `input` array (or string) plus `instructions`, a typed `output` array, a different
 * usage shape that includes reasoning tokens, and semantic streaming events. This strategy maps the
 * generic [RawChatMessage] context (including tool calls and tool outputs) into Responses `input`
 * items, and maps the typed `output`/streaming events back into the generic [LLMCompletionResult] /
 * [LLMStreamChunk] models.
 *
 * Reasoning support: when the model is reasoning-capable, the generated reasoning items (and the
 * response `id`) are captured in the result `metadata`. This enables higher layers to persist and
 * replay reasoning context across turns (either by threading `previous_response_id` for stored
 * responses, or by appending captured reasoning `input` items).
 *
 * @property json The Json instance used for serialization/deserialization.
 * @property providerType The provider type this strategy serves. Though the Responses endpoint is
 *            OpenAI-specific, it is expressed through the OPENAI provider type so the client can
 *            resolve it for OpenAI models.
 */
class ResponsesStrategy(
    private val json: Json,
) : ChatCompletionStrategy {

    private val logger: Logger = LogManager.getLogger(ResponsesStrategy::class.java)

    override val providerType: LLMProviderType = LLMProviderType.OPENAI

    override fun prepareRequest(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?
    ): Either<LLMCompletionError.ConfigurationError, ApiRequestConfig> {
        logger.debug("Preparing Responses request for model ${modelConfig.name}")

        if (provider.apiKeyId != null && apiKey == null) {
            return LLMCompletionError.ConfigurationError(
                "OpenAI Responses provider '${provider.name}' requires an API key, but none was provided."
            ).left()
        }

        // The Responses strategy only understands ResponsesModelSettings, which the caller routes here.
        if (settings !is ResponsesModelSettings) {
            return LLMCompletionError.ConfigurationError(
                "ResponsesStrategy requires ResponsesModelSettings but received ${settings::class.simpleName}."
            ).left()
        }

        val inputItems = messages.flatMap { it.toResponsesInput() }

        val requestBodyJson = buildJsonObject {
            // Start with custom parameters from settings, allowing forwards-compatible overrides.
            settings.customParams?.let { params ->
                params.forEach { (key, value) -> put(key, value) }
            }

            put("model", JsonPrimitive(modelConfig.name))
            put("input", JsonArray(inputItems))
            put("stream", JsonPrimitive(settings.stream))

            settings.instructions?.let { put("instructions", JsonPrimitive(it)) }
            settings.temperature?.let { put("temperature", JsonPrimitive(it)) }
            settings.maxOutputTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
            settings.topP?.let { put("top_p", JsonPrimitive(it)) }
            settings.stopSequences?.takeIf { it.isNotEmpty() }?.let {
                put("stop", json.encodeToJsonElement(it))
            }

            settings.reasoningEffort?.let { effort ->
                put("reasoning", buildJsonObject {
                    put("effort", JsonPrimitive(effort))
                })
            }

            // Stored responses allow `previous_response_id` chaining on subsequent turns. We expose
            // `store` from settings; `previous_response_id` itself is not yet threaded from session
            // state, so we rely on either stateless input replay or store ahead of a follow-up.
            put("store", JsonPrimitive(settings.store))

            if (!tools.isNullOrEmpty()) {
                val apiTools = tools.map { mapToolDefinition(it) }
                put("tools", json.encodeToJsonElement(apiTools))
                put("tool_choice", JsonPrimitive("auto"))
                logger.debug("Added ${tools.size} tools to Responses request")
            }
        }

        val customHeaders = buildMap {
            if (apiKey != null) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }

        val requestBodyString = json.encodeToString(JsonObject.serializer(), requestBodyJson)
        logger.debug("Prepared Responses request body: ${requestBodyString.take(500)}...")

        return ApiRequestConfig(
            path = "/responses",
            method = GenericHttpMethod.POST,
            body = requestBodyString,
            contentType = GenericContentType.APPLICATION_JSON,
            customHeaders = customHeaders
        ).right()
    }

    override fun processSuccessResponse(
        responseBody: String
    ): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult> {
        logger.debug("Processing Responses success response body: ${responseBody.take(500)}...")
        return try {
            val response: ResponsesApiModels.ResponsesResponse = json.decodeFromString(responseBody)

            var content: String? = null
            var finishReason: String? = "stop"
            val toolCalls = mutableListOf<LLMCompletionResult.CompletionChoice.ToolCallRequest>()

            for (item in response.output) {
                when (item.type) {
                    "message" -> {
                        // Concatenate output_text content from all content parts.
                        val text = item.content?.filter { it.type == "output_text" }?.mapNotNull { it.text }
                            ?.joinToString(separator = "")
                        if (!text.isNullOrBlank()) {
                            content = (content ?: "") + text
                        }
                    }

                    "function_call" -> {
                        toolCalls.add(
                            LLMCompletionResult.CompletionChoice.ToolCallRequest(
                                name = item.name ?: "",
                                arguments = item.arguments,
                                toolCallId = item.callId
                            )
                        )
                    }
                }
            }

            // A successful response that produced function calls should be treated as a tool-calling
            // step so the orchestrator can execute them and continue the loop.
            if (toolCalls.isNotEmpty()) {
                finishReason = "tool_calls"
            }

            val contentValue = content

            val result = LLMCompletionResult(
                choices = listOf(
                    LLMCompletionResult.CompletionChoice(
                        role = "assistant",
                        content = contentValue,
                        finishReason = finishReason,
                        index = 0,
                        toolCalls = toolCalls.ifEmpty { null }
                    )
                ),
                usage = LLMCompletionResult.UsageStats(
                    promptTokens = response.usage?.inputTokens ?: 0,
                    completionTokens = response.usage?.outputTokens ?: 0,
                    totalTokens = response.usage?.totalTokens ?: 0
                ),
                id = response.id,
                metadata = buildMap {
                    put("api_object", "response")
                    put("api_model", response.model)
                    put("api_status", response.status)
                    put("reasoning_effort", response.reasoning?.effort)
                    response.usage?.outputTokensDetails?.reasoningTokens?.let {
                        put("reasoning_tokens", it)
                    }
                    // Capture reasoning summary/text items so higher layers can persist and replay them.
                    val reasoningItems = response.output.filter { it.type == "reasoning" }
                    if (reasoningItems.isNotEmpty()) {
                        put("reasoning_items", reasoningItems)
                    }
                }
            )
            logger.debug("Parsed Responses response with ${result.choices.size} choice(s)")
            result.right()
        } catch (e: Exception) {
            logger.error("Failed to parse Responses success response body", e)
            LLMCompletionError.InvalidResponseError(
                "Failed to parse Responses success response body: ${e.message}", e
            ).left()
        }
    }

    override fun processErrorResponse(statusCode: Int, errorBody: String): LLMCompletionError {
        logger.debug("Processing Responses error body (Status $statusCode): ${errorBody.take(500)}...")
        val apiErrorMessage = try {
            json.decodeFromString<ResponsesApiModels.OpenAiErrorResponse>(errorBody).error.message
        } catch (e: Exception) {
            logger.warn("Failed to parse Responses error body, using raw body.", e)
            errorBody.take(200)
        }

        return when (statusCode) {
            401, 403 -> LLMCompletionError.AuthenticationError("OpenAI Responses API authentication failed: $apiErrorMessage")
            404 -> LLMCompletionError.ApiError(
                statusCode,
                "OpenAI Responses API endpoint or model not found: $apiErrorMessage",
                errorBody
            )

            429 -> LLMCompletionError.ApiError(
                statusCode,
                "OpenAI Responses API rate limit exceeded: $apiErrorMessage",
                errorBody
            )

            else -> LLMCompletionError.ApiError(
                statusCode,
                "OpenAI Responses API returned error $statusCode: $apiErrorMessage",
                errorBody
            )
        }
    }

    override fun processStreamingResponse(
        responseStream: Flow<String>
    ): Flow<Either<LLMCompletionError.InvalidResponseError, LLMStreamChunk>> = flow {
        logger.debug("Processing Responses streaming response")

        // Track the identity of each in-progress tool call, keyed by output_index. The function-call
        // identity (name and call_id) is only carried by the `response.output_item.added` event that
        // introduces the call; the subsequent `response.function_call_arguments.delta` events are routed
        // to the same tool call via their `output_index` and carry no name/id themselves.
        //
        // The upstream consumer groups ToolCallChunks by `index` (= output_index) and uses the first
        // chunk's name/id, so we attach the captured name and call_id to each emitted delta chunk.
        val toolCallMetaByOutputIndex = LinkedHashMap<Int, ToolCallMeta>()

        responseStream.collect { rawChunk ->
            try {
                if (rawChunk.isBlank()) return@collect
                if (!rawChunk.startsWith("data: ")) {
                    logger.trace("Skipping non-data line: $rawChunk")
                    return@collect
                }

                val dataContent = rawChunk.removePrefix("data: ").trim()
                if (dataContent == "[DONE]") return@collect

                val event: ResponsesApiModels.StreamEvent = json.decodeFromString(dataContent)

                when (event.type) {
                    "response.output_text.delta" -> {
                        val delta = event.delta ?: ""
                        if (delta.isNotEmpty()) {
                            emit(LLMStreamChunk.ContentChunk(deltaContent = delta).right())
                        }
                    }

                    "response.output_item.added" -> {
                        // A function call is announced here with its name and call_id. Capture them keyed
                        // by the event's output_index so subsequent argument deltas can be attributed to it.
                        val item = event.item
                        if (item?.type == "function_call") {
                            val outputIndex = event.outputIndex ?: return@collect
                            toolCallMetaByOutputIndex[outputIndex] =
                                ToolCallMeta(name = item.name ?: "", callId = item.callId)
                        }
                    }

                    "response.function_call_arguments.delta" -> {
                        val outputIndex = event.outputIndex ?: return@collect
                        val argumentsDelta = event.delta ?: ""
                        val meta = toolCallMetaByOutputIndex[outputIndex]
                        emit(
                            LLMStreamChunk.ToolCallChunk(
                                index = outputIndex,
                                id = meta?.callId,
                                name = meta?.name,
                                argumentsDelta = argumentsDelta
                            ).right()
                        )
                    }

                    "response.completed" -> {
                        // Usage is delivered on this terminal event in the embedded response snapshot.
                        event.response?.usage?.let { usage ->
                            emit(
                                LLMStreamChunk.UsageChunk(
                                    promptTokens = usage.inputTokens ?: 0,
                                    completionTokens = usage.outputTokens ?: 0,
                                    totalTokens = usage.totalTokens ?: 0
                                ).right()
                            )
                        }
                        emit(LLMStreamChunk.Done.right())
                        return@collect
                    }

                    "error" -> {
                        emit(
                            LLMStreamChunk.Error(
                                LLMCompletionError.ApiError(
                                    statusCode = 500,
                                    message = event.message ?: "Unknown Responses streaming error",
                                    errorBody = dataContent
                                )
                            ).right()
                        )
                    }

                    else -> {
                        // Ignore lifecycle events (created, in_progress, output_item.added, etc.).
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to parse Responses streaming JSON chunk: $rawChunk", e)
                emit(
                    LLMCompletionError.InvalidResponseError(
                        "Failed to parse Responses streaming JSON chunk: ${e.message}"
                    ).left()
                )
            }
        }
    }

    /**
     * Converts a [RawChatMessage] into Responses API input items.
     *
     * User and assistant text messages become `message` items. Assistant tool calls become separate
     * `function_call` items, and tool results become `function_call_output` items, matching the
     * Responses item-based input format used for manual context management.
     *
     * @receiver The raw message to convert.
     * @return A list of Responses API input item JsonObjects. Assistant messages with tool calls and
     *         their matching outputs produce multiple items.
     */
    private fun RawChatMessage.toResponsesInput(): List<JsonObject> = when (this) {
        is RawChatMessage.User -> listOf(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("input_text"))
                        put("text", JsonPrimitive(content))
                    })
                })
            }
        )

        is RawChatMessage.Assistant -> buildList {
            if (content != null) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("output_text"))
                            put("text", JsonPrimitive(content))
                        })
                    })
                })
            }
            toolCalls?.forEach { toolCall ->
                add(buildJsonObject {
                    put("type", JsonPrimitive("function_call"))
                    put("call_id", JsonPrimitive(toolCall.id ?: ""))
                    put("name", JsonPrimitive(toolCall.name))
                    toolCall.arguments?.let { put("arguments", JsonPrimitive(it)) }
                })
            }
        }

        is RawChatMessage.Tool -> listOf(
            buildJsonObject {
                put("type", JsonPrimitive("function_call_output"))
                put("call_id", JsonPrimitive(toolCallId))
                put("output", JsonPrimitive(content))
            }
        )
    }

    /**
     * Converts a domain [ToolDefinition] into the Responses API function tool format, which reuses the
     * Chat Completions function schema (name, description, parameters, strict).
     *
     * @param tool The domain tool definition.
     * @return A Responses API tool JsonObject.
     */
    private fun mapToolDefinition(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("name", JsonPrimitive(tool.name))
        put("description", JsonPrimitive(tool.description))
        put("parameters", tool.inputSchema)
        put("strict", JsonPrimitive(false))
    }

    /**
     * Captured identity of an in-progress function call, obtained from its `response.output_item.added`
     * event and used to decorate the argument-delta [LLMStreamChunk.ToolCallChunk] instances that follow.
     *
     * @property name The function name invoked by the model.
     * @property callId The `call_id` used to later submit the function-call output back to the API.
     */
    private data class ToolCallMeta(
        val name: String,
        val callId: String?,
    )
}

