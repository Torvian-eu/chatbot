package eu.torvian.chatbot.common.models.api.llm

import kotlinx.serialization.Serializable

/**
 * Represents a model discovered directly from a provider endpoint.
 *
 * @property id Provider-specific model identifier used in completion requests.
 * @property displayName Optional display name suitable for UI presentation.
 * @property metadata Additional provider-specific attributes as string key/value pairs.
 */
@Serializable
data class DiscoveredProviderModel(
    val id: String,
    val displayName: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

