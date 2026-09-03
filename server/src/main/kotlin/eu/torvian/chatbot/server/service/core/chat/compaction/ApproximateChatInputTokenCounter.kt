package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import arrow.core.left
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategyResolver
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Deterministic v1 implementation of [ChatInputTokenCounter].
 *
 * The estimate serializes the strategy's compact input-only projection with the shared [Json]
 * instance (the exact encoding `prepareRequest` uses), counts the resulting Kotlin UTF-16 code units
 * (i.e. `String.length`), and returns `ceil(codeUnits / 4)` — `(codeUnits + 3) / 4` — as a [Long].
 * JSON field names, delimiters, roles, tool-call IDs/names/arguments, schemas, and provider-specific
 * item wrappers therefore contribute their required format overhead, while model/output settings and
 * the completion budget do not. This is an intentional v1 approximation (about four characters per
 * token), not provider-exact tokenization: non-BMP characters count as two code units, combining
 * marks count independently, and JSON escaping contributes its serialized length.
 *
 * @property strategyResolver Shared strategy-selection rule used by the request path.
 * @property json Shared compact JSON codec used to serialize projections.
 */
class ApproximateChatInputTokenCounter(
    private val strategyResolver: ChatCompletionStrategyResolver,
    private val json: Json
) : ChatInputTokenCounter {

    /** Stable version identifier persisted with chunk count metadata. */
    override val version: String = "approx_utf16_json_v1"

    override fun countPrimaryInput(
        model: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        systemMessage: String?,
        messages: List<RawChatMessage>,
        tools: List<ToolDefinition>?
    ): Either<ConversationCompactionError, Long> {
        // Resolve through the same rule the HTTP client uses so an unsupported dialect fails exactly
        // where a real request would fail, instead of producing an undercounted projection.
        val strategy = strategyResolver.resolve(settings, provider)
            ?: return ConversationCompactionError.UnsupportedConfiguration(
                "No chat completion strategy is registered for provider type ${provider.type} " +
                        "with settings ${settings::class.simpleName}"
            ).left()

        return strategy
            .buildInputProjection(
                messages = messages,
                modelConfig = model,
                provider = provider,
                settings = settings,
                systemMessage = systemMessage,
                tools = tools
            )
            .mapLeft { error ->
                ConversationCompactionError.UnsupportedConfiguration(
                    "Cannot project primary input for ${provider.name}/${model.name}: ${error.message}"
                )
            }
            .map { projection ->
                val codeUnits = json.encodeToString(JsonObject.serializer(), projection).length.toLong()
                // Ceiling division by four: a projection cannot be empty in practice (it always carries
                // at least the messages wrapper), but the formula is defined for the empty case too.
                if (codeUnits == 0L) 0L else (codeUnits + 3L) / 4L
            }
    }
}
