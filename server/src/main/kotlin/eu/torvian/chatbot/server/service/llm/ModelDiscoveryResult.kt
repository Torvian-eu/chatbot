package eu.torvian.chatbot.server.service.llm

/**
 * Generic model discovery result returned by provider-specific discovery strategies.
 *
 * @property models List of models discovered from the remote provider endpoint.
 */
data class ModelDiscoveryResult(
    val models: List<DiscoveredModel>
) {
    /**
     * Generic representation of a remotely discovered model.
     *
     * @property id Provider-specific model identifier used for API calls.
     * @property displayName Optional user-facing display name when available.
     * @property metadata Additional provider-specific attributes flattened into string key/value pairs.
     */
    data class DiscoveredModel(
        val id: String,
        val displayName: String? = null,
        val metadata: Map<String, String> = emptyMap()
    )
}

