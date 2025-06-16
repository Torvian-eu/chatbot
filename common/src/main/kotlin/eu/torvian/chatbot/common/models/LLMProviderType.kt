package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents the different types of LLM providers supported by the system.
 */
@Serializable
enum class LLMProviderType {
    /**
     * OpenAI provider (GPT models).
     */
    OPENAI,
    
    /**
     * OpenRouter provider (multiple model access).
     */
    OPENROUTER,
    
    /**
     * Anthropic provider (Claude models).
     */
    ANTHROPIC,

    /**
     * Ollama provider for local LLM hosting.
     */
    OLLAMA,

    /**
     * Custom provider for self-hosted or other API-compatible endpoints.
     */
    CUSTOM
}
