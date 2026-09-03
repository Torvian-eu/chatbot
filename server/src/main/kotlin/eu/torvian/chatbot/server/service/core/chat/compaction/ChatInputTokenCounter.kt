package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.ToolDefinition
import eu.torvian.chatbot.server.service.llm.RawChatMessage

/**
 * Estimates the primary-provider input token count that a request would consume.
 *
 * The estimate is used to decide whether automated compaction must run before a primary LLM call.
 * It is deliberately repository-owned and deterministic (no third-party tokenizer); the exact v1
 * formula is documented on [eu.torvian.chatbot.server.service.core.chat.compaction.ApproximateChatInputTokenCounter].
 */
interface ChatInputTokenCounter {

    /**
     * Stable version identifier persisted with every chunk's count metadata.
     *
     * Changing the counting formula in a later version must introduce a new version string; old
     * chunk counts remain audit metadata and do not affect eligibility.
     */
    val version: String

    /**
     * Counts the input-bearing projection of a primary provider request.
     *
     * @param model The model the request would target (used for dialect-specific projections).
     * @param provider The provider owning [model].
     * @param settings The settings profile selecting the API dialect.
     * @param systemMessage Composed system prompt, or null/blank when absent.
     * @param messages Conversation context to be sent.
     * @param tools Enabled tool definitions, or null when the model has no tool capability.
     * @return Either an [ConversationCompactionError.UnsupportedConfiguration] when no strategy can
     *         serve the dialect, or the approximate token count.
     */
    fun countPrimaryInput(
        model: LLMModel,
        provider: LLMProvider,
        settings: ModelSettings,
        systemMessage: String?,
        messages: List<RawChatMessage>,
        tools: List<ToolDefinition>?
    ): Either<ConversationCompactionError, Long>
}
