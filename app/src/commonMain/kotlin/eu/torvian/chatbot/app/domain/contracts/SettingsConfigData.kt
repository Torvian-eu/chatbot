package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Data class that combines models and settings for unified state management in settings configuration.
 */
data class SettingsConfigData(
    val models: List<LLMModel>,
    val settings: List<ModelSettings>
)
