package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider

/**
 * Data class that combines models and providers for unified state management.
 */
data class ModelConfigData(
    val models: List<LLMModelDetails>,
    val providers: List<LLMProvider>
)
