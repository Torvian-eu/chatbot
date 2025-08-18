package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.ModelSettings

/**
 * Data class to hold LLM configuration components.
 */
data class LLMConfig(
    val provider: LLMProvider,
    val model: LLMModel,
    val settings: ModelSettings,
    val apiKey: String?
)