package eu.torvian.chatbot.server.service.llm

/**
 * Configuration details for making an external API request, independent of specific HTTP client libraries.
 * This is produced by a ChatCompletionStrategy and consumed by the LLMApiClient implementation.
 *
 * @property path The path segment for the API endpoint, relative to the provider's base URL.
 * @property method The HTTP method to use (e.g., POST).
 * @property body The request body. This should be an object that the HTTP client's serialization
 *                feature can handle (e.g., a @Serializable data class for JSON).
 * @property contentType The content type of the request body.
 * @property customHeaders Any additional headers required for this specific API call (e.g., API keys, versioning).
 */
data class ApiRequestConfig(
    val path: String,
    val method: GenericHttpMethod = GenericHttpMethod.POST,
    val body: Any,
    val contentType: GenericContentType,
    val customHeaders: Map<String, String> = emptyMap()
)
