package eu.torvian.chatbot.server.service.llm.strategy

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.util.Locale

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

    private companion object {
        /**
         * Upper bound, in characters, of provider-supplied text copied into one log line, so a single
         * provider ending cannot produce an unbounded log entry.
         */
        const val PROVIDER_DETAIL_LOG_CHARS: Int = 500

        /**
         * Upper bound, in characters, of the provider classification token kept on an ending.
         *
         * The token is copied into the transient wire `details` string and into downstream log lines, so it is
         * cut where the ending is built. No token family is that long, so a cut token can only lose a match it
         * would not have made anyway.
         */
        const val PROVIDER_CODE_MAX_CHARS: Int = 64

        /**
         * `response.status` values that mean the provider did not complete the generation, even though the
         * HTTP response carried a success status.
         *
         * Matches the documented `status` enum minus its success and in-flight values: `failed` and
         * `incomplete` are the documented non-success endings, and `cancelled` is emitted by
         * OpenAI-compatible proxies (the strategy also serves OpenRouter). Providers and proxies are not
         * consistent about the casing, so the status is lowercased before it is looked up here.
         */
        val PROVIDER_DECLARED_NON_SUCCESS_STATUSES: Set<String> = setOf("failed", "incomplete", "cancelled")
    }

    override val providerType: LLMProviderType = LLMProviderType.OPENAI

    override fun prepareRequest(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        apiKey: String?,
        tools: List<ToolDefinition>?,
        systemMessage: String?
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

        // The target model's reasoning mode drives what may be replayed: encrypted-mode models reject
        // non-empty plaintext `content`, while encrypted payloads are only replayable to the exact model
        // that produced them (checked per item against `targetModelId`). Unknown (null) defaults to
        // unencrypted, which keeps the historical plaintext `content` replay behavior.
        // Input-bearing fields (input items, instructions, tools, tool_choice) are built once and
        // embedded verbatim into the request body so the token counter shares the exact projection.
        val inputProjection = buildInputProjection(
            messages = messages,
            modelConfig = modelConfig,
            provider = provider,
            settings = settings,
            systemMessage = systemMessage,
            tools = tools
        ).getOrElse { error -> return error.left() }

        val requestBodyJson = buildJsonObject {
            // Start with custom parameters from settings, allowing forwards-compatible overrides.
            settings.customParams?.let { params ->
                params.forEach { (key, value) -> put(key, value) }
            }

            put("model", JsonPrimitive(modelConfig.name))
            put("stream", JsonPrimitive(settings.stream))

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

            // OpenRouter rejects `store:true` on its Responses-compatible endpoint, so requests routed
            // through it always disable server-side storage regardless of the settings knob. All other
            // providers honor `settings.store`; when enabled, the response is stored server-side and can
            // be referenced via `previous_response_id` in later turns.
            val store = provider.type != LLMProviderType.OPENROUTER && settings.store
            put("store", JsonPrimitive(store))

            // Input-bearing fields are merged last so generation/storage settings can never override them.
            inputProjection.forEach { (key, value) -> put(key, value) }
        }

        val customHeaders = buildMap {
            if (apiKey != null) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }

            // Attribution belongs only on OpenRouter requests; this strategy is also reused by OpenAI.
            if (provider.type == LLMProviderType.OPENROUTER) {
                putAll(OpenRouterClientInfo.headers)
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

    override fun buildInputProjection(
        messages: List<RawChatMessage>,
        modelConfig: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        systemMessage: String?,
        tools: List<ToolDefinition>?
    ): Either<LLMCompletionError.ConfigurationError, JsonObject> {
        if (settings !is ResponsesModelSettings) {
            return LLMCompletionError.ConfigurationError(
                "ResponsesStrategy requires ResponsesModelSettings but received ${settings::class.simpleName}."
            ).left()
        }

        // The target model's reasoning mode drives what may be replayed, mirroring prepareRequest so
        // the counted projection matches the actual `input` payload byte-for-byte.
        val reasoningEncrypted = modelConfig.isReasoningEncrypted()
        val inputItems = messages.flatMap {
            it.toResponsesInput(
                replayReasoning = settings.replayReasoning,
                reasoningEncrypted = reasoningEncrypted,
                targetModelId = modelConfig.id,
            )
        }

        return buildJsonObject {
            put("input", JsonArray(inputItems))
            // The composed system prompt is the single source of truth; it maps to `instructions`.
            if (!systemMessage.isNullOrBlank()) {
                put("instructions", JsonPrimitive(systemMessage))
            }
            if (!tools.isNullOrEmpty()) {
                val apiTools = tools.map { mapToolDefinition(it) }
                put("tools", json.encodeToJsonElement(apiTools))
                put("tool_choice", JsonPrimitive("auto"))
            }
        }.right()
    }

    override fun processSuccessResponse(
        responseBody: String
    ): Either<LLMCompletionError.InvalidResponseError, LLMCompletionResult> {
        logger.debug("Processing Responses success response body: ${responseBody.take(500)}...")
        return try {
            // Decode the response body once as a raw object and pull out the pieces we care about into
            // named locals for readability. Reasoning items are kept as raw JsonObjects so the strategy
            // boundary preserves all fields; sanitization to the replay-safe `input` shape happens later,
            // when items are persisted or replayed.
            val response = json.decodeFromJsonElement(JsonObject.serializer(), Json.parseToJsonElement(responseBody))
            val outputItems = response["output"]?.jsonArray?.filterIsInstance<JsonObject>().orEmpty()

            val responseId = response["id"]?.jsonPrimitive?.contentOrNull
            val responseModel = response["model"]?.jsonPrimitive?.contentOrNull
            val responseStatus = response["status"]?.jsonPrimitive?.contentOrNull

            // The status enum is not case-stable across providers and proxies, so it is normalised before it is
            // looked up. A non-success status is not reported here: the body still has to be mapped first, so
            // the ending can travel with the partial output it explains instead of replacing it.
            val declaredEndingStatus = responseStatus
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it in PROVIDER_DECLARED_NON_SUCCESS_STATUSES }

            // A missing or JSON-null usage (`usage: null` is what providers send when they did not complete the
            // generation) must not fail the mapping: only a real object contributes token counts.
            val usage = response["usage"] as? JsonObject
            val promptTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val completionTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val totalTokens = usage?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val reasoningTokens = usage?.get("output_tokens_details")?.jsonObject
                ?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull

            val reasoningEffort = response["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull

            // Reasoning items are emitted verbatim so higher layers can persist (sanitized) and replay them
            // across turns. They are opaque (may include OpenAI-encrypted content) and never rendered.
            val reasoningItems = outputItems
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }

            var content: String? = null
            val toolCalls = mutableListOf<LLMCompletionResult.CompletionChoice.ToolCallRequest>()

            for (item in outputItems) {
                when (item["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> {
                        // Concatenate the text of every content part: generated text carries it in an `output_text`
                        // part, a refusal in a `refusal` part, and both are the model's answer.
                        val text = item["content"]?.jsonArray
                            ?.mapNotNull { it.jsonObject }
                            ?.mapNotNull { part ->
                                when (part["type"]?.jsonPrimitive?.contentOrNull) {
                                    "output_text" -> part["text"]?.jsonPrimitive?.contentOrNull
                                    "refusal" -> part["refusal"]?.jsonPrimitive?.contentOrNull
                                    else -> null
                                }
                            }
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

            // A 2xx body is not proof of a completed generation: the provider returns the same envelope (with an
            // empty `output[]`) for a response it declared failed, incomplete or cancelled, and for an
            // `incomplete` response that envelope still carries the partial answer. Reporting the ending as a
            // field of the result keeps it inseparable from that mapped output, so the caller cannot persist the
            // failure without the content it explains. The provider code/message stay in the logs.
            val providerFailure = declaredEndingStatus?.let { status ->
                providerDeclaredResponseFailure(
                    source = "responses.body status=$status",
                    response = response,
                    fallbackCode = status
                )
            }

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
                },
                providerFailure = providerFailure
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

    /**
     * Reads the provider's own classification token from a terminal Responses payload.
     *
     * The API reports the cause of a non-success ending in one of two places: `error.code` (on `response.failed`
     * and on a response whose `status` is `failed`) or `incomplete_details.reason` (on `response.incomplete`, e.g.
     * `max_output_tokens`). Both are read in that order, so a payload carrying only one of them still yields a
     * usable token; [fallbackCode] is used for payloads that carry neither (for instance the `status` value of a
     * non-success completion event).
     *
     * The token is a log/classification input only: it selects the persisted
     * [eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode] but is never persisted itself and never
     * becomes part of a user-visible reason.
     *
     * @param response The embedded `response` snapshot of the event, or `null` when the event carries none.
     * @param fallbackCode Token to use when the payload declares no code or reason of its own.
     * @return The provider's classification token, or `null` when none could be determined. A non-primitive
     *         `code`/`reason` counts as absent rather than as a parse failure, so a malformed payload cannot turn
     *         a terminal ending into a strategy-level error.
     */
    private fun providerDeclaredCode(response: JsonObject?, fallbackCode: String?): String? =
        ((response?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: ((response?.get("incomplete_details") as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
            ?: fallbackCode

    /**
     * Builds the server-internal error that represents a provider-declared non-success ending.
     *
     * The error only marks *how* the stream ended: the persisted code and reason are derived from
     * [providerCode] by [eu.torvian.chatbot.server.service.llm.toAssistantMessageCompletionState], so this message
     * never reaches the user. It is therefore deliberately provider-free and only states the shape of the ending,
     * while the provider code remains in the record and in the log line written by
     * [logProviderDeclaredEnding].
     *
     * @param providerCode Provider classification token of the ending, or `null` when it declared none. It is cut
     *        at [PROVIDER_CODE_MAX_CHARS], because it is copied into the transient wire `details` string and into
     *        downstream log lines.
     * @return The error to emit as an [LLMStreamChunk.Error] chunk (or to report on the non-streaming result).
     */
    private fun providerDeclaredFailure(providerCode: String?): LLMCompletionError.ProviderFailureError =
        LLMCompletionError.ProviderFailureError(
            providerCode = providerCode?.take(PROVIDER_CODE_MAX_CHARS),
            message = "The provider ended the response without completing it."
        )

    /**
     * Turns one provider-declared non-success `response` snapshot into the error that reports it.
     *
     * Shared by the three terminal streaming arms that carry a `response` snapshot (`response.failed`,
     * `response.incomplete` and a `response.completed` whose `status` is not `completed`) and by the non-streaming
     * status check, so every one of them classifies and logs identically and each writes exactly one log line.
     *
     * @param source Event type (or, for the non-streaming path, the body status) that declared the ending; it is
     *        the log discriminator that tells the four call sites apart.
     * @param response The embedded `response` snapshot, or `null` when the payload carried none.
     * @param fallbackCode Token to use when the payload declares no code or reason of its own.
     * @return The provider-failure error describing the ending.
     */
    private fun providerDeclaredResponseFailure(
        source: String,
        response: JsonObject?,
        fallbackCode: String?
    ): LLMCompletionError.ProviderFailureError {
        val providerCode = providerDeclaredCode(response, fallbackCode)
        logProviderDeclaredEnding(
            source = source,
            responseId = (response?.get("id") as? JsonPrimitive)?.contentOrNull,
            providerCode = providerCode,
            providerMessage = ((response?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)
                ?.contentOrNull
        )
        return providerDeclaredFailure(providerCode)
    }

    /**
     * Logs one provider-declared non-success ending with every detail the provider supplied.
     *
     * These details are log-only: the provider code, the provider message and the response id are operator
     * diagnostics, while everything user-visible comes from the bounded, server-authored templates in
     * [eu.torvian.chatbot.server.service.llm.toAssistantMessageCompletionState]. Every provider-supplied field is
     * cut at [PROVIDER_DETAIL_LOG_CHARS], so one ending cannot produce an unbounded log entry (a provider could
     * otherwise inflate or forge entries through an oversized code, message or response id).
     *
     * @param source Event type (or non-streaming status) that declared the ending.
     * @param responseId Provider response identifier, or `null` when the payload carried none.
     * @param providerCode Provider classification token, or `null` when the payload carried none.
     * @param providerMessage Provider-authored message, or `null` when the payload carried none.
     */
    private fun logProviderDeclaredEnding(
        source: String,
        responseId: String?,
        providerCode: String?,
        providerMessage: String?
    ) {
        logger.error(
            "Responses provider outcome is not a completion ({}): response id '{}', provider code '{}', provider message '{}'",
            source,
            responseId?.take(PROVIDER_DETAIL_LOG_CHARS) ?: "-",
            providerCode?.take(PROVIDER_DETAIL_LOG_CHARS) ?: "-",
            providerMessage?.take(PROVIDER_DETAIL_LOG_CHARS) ?: "-"
        )
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
                // named locals. Reasoning items are kept as raw JsonObjects so the strategy boundary preserves
                // all fields; sanitization to the replay-safe `input` shape happens later, when items are
                // persisted or replayed.
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
                        // raw item object (preserving e.g. `summary[].type`) so higher layers can persist and
                        // replay it. The payload is opaque and never rendered; it is sanitized to the
                        // replay-safe `input` shape at persistence/replay time.
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
     * @param replayReasoning Whether stored reasoning items should be replayed into the `input`.
     * @param reasoningEncrypted The current model's [eu.torvian.chatbot.common.models.llm.LLMModelCapabilities.REASONING_ENCRYPTED]
     *            value, or `null` when unknown (unknown defaults to unencrypted).
     * @param targetModelId The ID of the model now being called; encrypted reasoning payloads are only
     *            replayed when they were produced by this exact model.
     * @return A list of Responses API input item JsonObjects. Assistant messages with tool calls and
     *         their matching outputs produce multiple items.
     */
    private fun RawChatMessage.toResponsesInput(
        replayReasoning: Boolean,
        reasoningEncrypted: Boolean?,
        targetModelId: Long,
    ): List<JsonObject> = when (this) {
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
            // Reasoning items precede their assistant content in Responses input. They are already sanitized;
            // adapt them only for the target's reasoning mode and provenance, skipping non-replayable items.
            // Gated by `replayReasoning`.
            if (replayReasoning) {
                reasoningItems?.forEach { reasoningItem ->
                    adaptReasoningItemForReplay(
                        reasoningItem = reasoningItem,
                        reasoningEncrypted = reasoningEncrypted,
                        sourceModelId = reasoningModelId,
                        targetModelId = targetModelId,
                    )?.let { add(it) }
                }
            }

            add(buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("content", JsonPrimitive(content ?: ""))
            })

            toolCalls?.forEach { toolCall ->
                add(buildJsonObject {
                    put("type", JsonPrimitive("function_call"))
                    put("call_id", JsonPrimitive(toolCall.id ?: ""))
                    // Model-provided names may contain characters (spaces, punctuation, non-ASCII
                    // letters) that the Responses API rejects with a 400 on the `name` field, so the
                    // name is sanitized before it is written into the request.
                    put("name", JsonPrimitive(sanitizeToolName(toolCall.name)))
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
     * Sanitizes a model-supplied tool name so it satisfies the Responses API's `name` contract.
     *
     * OpenAI's Responses API rejects `function_call` items whose `name` does not match
     * `^[a-zA-Z0-9_-]+$` (HTTP 400 `Invalid 'input[2].name'`). Models occasionally hallucinate tool
     * identities containing spaces, punctuation, or non-ASCII letters; those names are persisted and
     * replayed into later requests, so every character outside the allowed ASCII set is replaced with
     * `_`. A name made solely of illegal characters would collapse to an empty string, which also
     * violates the pattern, so the empty result falls back to `"unknown_tool"`. The matching
     * `function_call_output` item is correlated via `call_id`, so the fallback name does not affect
     * tool-output matching.
     *
     * @param name The raw tool name produced by the model.
     * @return A non-empty name matching `^[a-zA-Z0-9_-]+$`.
     */
    private fun sanitizeToolName(name: String): String {
        val sanitized = name.map { character ->
            val allowed = character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '-' || character == '_'
            if (allowed) character else '_'
        }.joinToString("")
        // A name consisting only of illegal characters sanitizes to "", which is still invalid for
        // the pattern; use a stable placeholder so the request remains well-formed. Char ranges
        // compare by UTF-16 code unit, so non-ASCII letters are also replaced.
        return sanitized.ifEmpty { "unknown_tool" }
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

