package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Data class that combines models and providers for unified state management.
 */
data class ModelConfigData(
    val models: List<LLMModel>,
    val providers: List<LLMProvider>
)
