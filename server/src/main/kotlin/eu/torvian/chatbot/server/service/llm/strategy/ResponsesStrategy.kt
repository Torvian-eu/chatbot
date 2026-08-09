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

        val inputItems = messages.flatMap { it.toResponsesInput(replayReasoning = settings.replayReasoning) }

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

            // Reasoning context is replayed statelessly via the persisted `input` items (see
            // `replayReasoning`), so responses are never stored server-side and no `previous_response_id`
            // is threaded. This is required for OpenRouter (which rejects `store:true`) and keeps OpenAI
            // retries idempotent. The settings `store` knob is deliberately overridden here.
            put("store", JsonPrimitive(false))

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
            // Decode the response body once as a raw object and pull out the pieces we care about into
            // named locals for readability. Reasoning items are kept as raw JsonObjects so no field (e.g.
            // `summary[].type` or `encrypted_content`) is lost during round-trip.
            val response = json.decodeFromJsonElement(JsonObject.serializer(), Json.parseToJsonElement(responseBody))
            val outputItems = response["output"]?.jsonArray?.filterIsInstance<JsonObject>().orEmpty()

            val responseId = response["id"]?.jsonPrimitive?.contentOrNull
            val responseModel = response["model"]?.jsonPrimitive?.contentOrNull
            val responseStatus = response["status"]?.jsonPrimitive?.contentOrNull

            val usage = response["usage"]?.jsonObject
            val promptTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val completionTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val reasoningTokens = usage?.get("output_tokens_details")?.jsonObject
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull

            val reasoningEffort = response["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull

            // Reasoning items are emitted verbatim so higher layers can persist and replay them across turns.
            // They are opaque (may include OpenAI-encrypted content) and never rendered.
            val reasoningItems = outputItems
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }

            var content: String? = null
            val toolCalls = mutableListOf<LLMCompletionResult.CompletionChoice.ToolCallRequest>()

            for (item in outputItems) {
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> {
                        // Concatenate output_text content from all content parts.
                        val text = item["content"]?.jsonArray
                            ?.mapNotNull { it.jsonObject }
                            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
                            ?.joinToString(separator = "")
                        if (!text.isNullOrBlank()) {
                            content = (content ?: "") + text
                        }
                    }

                    "function_call" -> {
                        toolCalls.add(
                            LLMCompletionResult.CompletionChoice.ToolCallRequest(
                                name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                arguments = item["arguments"]?.jsonPrimitive?.contentOrNull,
                                toolCallId = item["call_id"]?.jsonPrimitive?.contentOrNull
                            )
                        )
                    }
                }
            }

            // A successful response that produced function calls should be treated as a tool-calling
            // step so the orchestrator can execute them and continue the loop.
            val finishReason = if (toolCalls.isNotEmpty()) "tool_calls" else "stop"

            val result = LLMCompletionResult(
                choices = listOf(
                    LLMCompletionResult.CompletionChoice(
                        role = "assistant",
                        content = content,
                        finishReason = finishReason,
                        index = 0,
                        toolCalls = toolCalls.ifEmpty { null }
                    )
                ),
                usage = LLMCompletionResult.UsageStats(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    reasoningTokens = reasoningTokens
                ),
                id = responseId,
                reasoningItems = reasoningItems.ifEmpty { null },
                metadata = buildMap {
                    put("api_object", "response")
                    put("api_model", responseModel)
                    put("api_status", responseStatus)
                    put("reasoning_effort", reasoningEffort)
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
        // Decode the OpenAI-compatible error envelope as a raw object and pull out the human-readable message.
        val apiErrorMessage = try {
            Json.parseToJsonElement(errorBody).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            logger.warn("Failed to parse Responses error body, using raw body.", e)
            null
        } ?: errorBody.take(200)

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

        // The Responses API `output_index` is a position in the whole `output[]` array, which may include
        // non-function items (e.g. reasoning) before any function call. The upstream consumer, however,
        // groups tool calls by a **sequential 0-based index** (ToolCallChunk.index) bounded by
        // MAX_TOOL_CALLS_PER_STEP. We therefore translate each function call's `output_index` into its
        // sequential position among the function calls, assigned in the order the calls are announced by
        // `response.output_item.added`.
        //
        // The function-call identity (name and call_id) is only carried by `response.output_item.added`;
        // the subsequent `response.function_call_arguments.delta` and `response.output_item.done` events are
        // routed to the same call via their `output_index` and carry no name/id themselves.
        val toolCallMetaByOutputIndex = LinkedHashMap<Int, ToolCallMeta>()
        var nextToolCallIndex = 0

        responseStream.collect { rawChunk ->
            try {
                if (rawChunk.isBlank()) return@collect
                if (!rawChunk.startsWith("data: ")) {
                    logger.trace("Skipping non-data line: $rawChunk")
                    return@collect
                }

                val dataContent = rawChunk.removePrefix("data: ").trim()
                if (dataContent == "[DONE]") return@collect

                // Decode the streamed event once as a raw object and pull out the fields we care about into
                // named locals. Reasoning items are kept as raw JsonObjects so no field (e.g. `summary[].type`
                // or `encrypted_content`) is lost during round-trip.
                val event = json.decodeFromJsonElement(JsonObject.serializer(), Json.parseToJsonElement(dataContent))
                val eventType = event["type"]?.jsonPrimitive?.contentOrNull
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull
                val outputIndex = event["output_index"]?.jsonPrimitive?.intOrNull
                val item = event["item"] as? JsonObject

                when (eventType) {
                    "response.output_text.delta" -> {
                        if (!delta.isNullOrEmpty()) {
                            emit(LLMStreamChunk.ContentChunk(deltaContent = delta).right())
                        }
                    }

                    "response.output_item.added" -> {
                        // A function call is announced here with its name and call_id. Capture them keyed
                        // by the event's output_index so subsequent argument deltas can be attributed to it,
                        // and assign its sequential tool-call index (see translation note above).
                        if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                            if (outputIndex == null) return@collect
                            toolCallMetaByOutputIndex.getOrPut(outputIndex) {
                                ToolCallMeta(
                                    sequentialIndex = nextToolCallIndex++,
                                    name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                    callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                )
                            }
                        }
                    }

                    "response.function_call_arguments.delta" -> {
                        if (outputIndex == null) return@collect
                        val meta = toolCallMetaByOutputIndex[outputIndex]
                        emit(
                            LLMStreamChunk.ToolCallChunk(
                                index = meta?.sequentialIndex,
                                id = meta?.callId,
                                name = meta?.name,
                                argumentsDelta = delta ?: ""
                            ).right()
                        )
                    }

                    "response.reasoning_text.delta" -> {
                        // Incremental plaintext chain-of-thought suitable for live UI rendering. Grouped by
                        // (output_index, content_index); the consumer concatenates deltas. Never persisted or
                        // replayed (only the opaque ReasoningDone item is), so skip empty deltas.
                        val reasoningDelta = event["delta"]?.jsonPrimitive?.contentOrNull
                        if (!reasoningDelta.isNullOrEmpty()) {
                            emit(
                                LLMStreamChunk.ReasoningTextChunk(
                                    outputIndex = outputIndex,
                                    contentIndex = event["content_index"]?.jsonPrimitive?.intOrNull ?: 0,
                                    delta = reasoningDelta
                                ).right()
                            )
                        }
                    }

                    "response.output_item.done" -> {
                        // The full output item is delivered on this event. For reasoning items, capture the
                        // raw item object verbatim (preserving e.g. `summary[].type` and `encrypted_content`)
                        // so higher layers can accumulate and persist it faithfully for replay. The payload is
                        // opaque and never rendered.
                        when (item?.get("type")?.jsonPrimitive?.contentOrNull) {
                            "reasoning" -> emit(
                                LLMStreamChunk.ReasoningDone(
                                    reasoningItem = item
                                ).right()
                            )

                            // The completed function_call item carries the authoritative final name, call_id
                            // and full arguments string. Providers may correct the raw delta stream in this
                            // final item, so consumers should prefer it over the delta-accumulated arguments.
                            // Unpack the wire fields here so downstream consumers stay API-independent, and
                            // use the same sequential tool-call index assigned at output_item.added time.
                            "function_call" -> emit(
                                LLMStreamChunk.ToolCallDone(
                                    index = outputIndex?.let { toolCallMetaByOutputIndex[it]?.sequentialIndex },
                                    id = item["call_id"]?.jsonPrimitive?.contentOrNull,
                                    name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                    arguments = item["arguments"]?.jsonPrimitive?.contentOrNull
                                ).right()
                            )
                        }
                    }

                    "response.completed" -> {
                        // Usage is delivered on this terminal event in the embedded response snapshot.
                        val usage = event["response"]?.jsonObject?.get("usage")?.jsonObject
                        if (usage != null) {
                            emit(
                                LLMStreamChunk.UsageChunk(
                                    promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                    completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                    totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                                    reasoningTokens = usage["output_tokens_details"]?.jsonObject
                                        ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull
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
                                    message = event["message"]?.jsonPrimitive?.contentOrNull
                                        ?: "Unknown Responses streaming error",
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
    private fun RawChatMessage.toResponsesInput(replayReasoning: Boolean): List<JsonObject> = when (this) {
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
            // Reasoning items must precede the assistant content they belong to in the Responses `input`.
            // They are emitted verbatim (preserving e.g. `encrypted_content`) so the model picks up prior
            // chain-of-thought. Gated by the `replayReasoning` setting.
            if (replayReasoning) {
                reasoningItems?.forEach { reasoningItem ->
                    add(reasoningItem)
                }
            }

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
     * Captured identity and sequential position of an in-progress function call, obtained from its
     * `response.output_item.added` event and used to decorate the argument-delta [LLMStreamChunk.ToolCallChunk]
     * and authoritative [LLMStreamChunk.ToolCallDone] instances that follow.
     *
     * @property sequentialIndex The 0-based position of this call among the response's function calls, matching
     *            the orchestrator's [LLMStreamChunk.ToolCallChunk.index] semantics (the provider's raw
     *            `output_index` may be offset by non-function output items).
     * @property name The function name invoked by the model.
     * @property callId The `call_id` used to later submit the function-call output back to the API.
     */
    private data class ToolCallMeta(
        val sequentialIndex: Int,
        val name: String,
        val callId: String?,
    )
}

