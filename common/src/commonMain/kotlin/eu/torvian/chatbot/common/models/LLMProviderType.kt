package eu.torvian.chatbot.common.models

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
     * OpenRouter provider (multiple model access via their gateway).
     * Often uses an OpenAI-compatible API format.
     */
    OPENROUTER,

    /**
     * Anthropic provider (Claude models). Has a distinct API format.
     */
    ANTHROPIC,

    /**
     * Ollama provider for local LLM hosting. Uses a different API format.
     */
    OLLAMA,

    /**
     * Custom provider for self-hosted or other API-compatible endpoints.
     * Might try to auto-detect or require specific configuration.
     */
    CUSTOM
}
