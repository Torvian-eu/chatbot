package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings

/**
 * Shared strategy-selection rule used by the HTTP LLM client and the input token counter.
 *
 * The settings subtype decides the API dialect: RESPONSES settings route to the registered Responses
 * strategy, all other settings use the strategy registered for the provider type. Centralizing the
 * rule here prevents the counting path and the request path from drifting apart.
 *
 * @property strategies Strategies registered per [LLMProviderType] for the non-Responses dialects.
 * @property responsesStrategy Optional Responses strategy; `null` means Responses is not registered.
 */
class ChatCompletionStrategyResolver(
    private val strategies: Map<LLMProviderType, ChatCompletionStrategy>,
    private val responsesStrategy: ChatCompletionStrategy? = null
) {
    /**
     * Resolves the strategy that would serve a request for the given settings/provider pair.
     *
     * @param settings The settings profile attached to the request, which determines the API dialect.
     * @param provider The owning provider.
     * @return The resolved strategy, or `null` if none is registered for the request.
     */
    fun resolve(settings: ModelSettings, provider: LLMProvider): ChatCompletionStrategy? {
        return when (settings) {
            is ResponsesModelSettings -> responsesStrategy
            else -> strategies[provider.type]
        }
    }
}
