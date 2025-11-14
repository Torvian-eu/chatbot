package eu.torvian.chatbot.server.service.core

import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.tool.ToolDefinition

/**
 * Data class to hold LLM configuration components.
 *
 * This configuration is created during request validation and passed through
 * the message processing pipeline. It contains all information needed to
 * make LLM API calls.
 *
 * @property provider The LLM provider (OpenAI, Ollama, etc.)
 * @property model The specific model configuration
 * @property settings Model settings (temperature, max tokens, etc.)
 * @property apiKey Optional API key for authentication
 * @property tools Optional list of tool definitions available for this request.
 *                 Null if the model doesn't support tool calling (no tool calling capability).
 *                 Empty list if the model supports tool calling but no tools are enabled for the session.
 *                 List with items if the model supports tool calling and specific tools are enabled.
 */
data class LLMConfig(
    val provider: LLMProvider,
    val model: LLMModel,
    val settings: ChatModelSettings,
    val apiKey: String?,
    val tools: List<ToolDefinition>? = null
)