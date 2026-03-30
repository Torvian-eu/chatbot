package eu.torvian.chatbot.common.models.api.llm

import eu.torvian.chatbot.common.models.llm.LLMProviderType
import kotlinx.serialization.Serializable

/**
 * Request body for testing provider connectivity without persisting a provider.
 *
 * @property baseUrl Base URL of the provider API.
 * @property type Provider type that determines strategy and protocol mapping.
 * @property credential Optional provider credential used for authenticated providers.
 */
@Serializable
data class TestProviderConnectionRequest(
    val baseUrl: String,
    val type: LLMProviderType,
    val credential: String? = null
)

