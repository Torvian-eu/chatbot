package eu.torvian.chatbot.common.models.llm

import kotlinx.serialization.Serializable

/**
 * Represents the different types of LLM providers supported by the system.
 * Used to select the correct API strategy and potentially configure client behavior.
 */
@Serializable
enum class LLMProviderType {
    /**
     * OpenAI provider (GPT models). Standard chat completion API.
     */
    OPENAI,

    /**
     * OpenRouter provider. Uses OpenAI-compatible chat endpoints and a dedicated models endpoint payload.
     */
    OPENROUTER,

    /**
     * Anthropic provider (Claude models). Has a distinct API format.
     */
    ANTHROPIC,

    /**
     * Ollama provider for local LLM hosting. Uses a different API format.
     */
    OLLAMA
}
