package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Data class to hold LLM configuration components.
 */
data class LLMConfig(
    val provider: LLMProvider,
    val model: LLMModel,
    val settings: ModelSettings,
    val apiKey: String?
)