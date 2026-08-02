package eu.torvian.chatbot.server.service.llm

/**
 * Supplies the stable product identity used when Torvian identifies itself to OpenRouter.
 * The identity is deliberately independent of the configured server URL because deployments
 * may use private, local, or tenant-specific addresses that are not suitable for attribution.
 */
object OpenRouterClientInfo {
    /**
     * Public product URL submitted as the application's OpenRouter referer.
     */
    const val SITE_URL: String = "https://chatbot.torvian.eu"

    /**
     * Product name displayed by OpenRouter for attributed requests.
     */
    const val TITLE: String = "Torvian Chatbot"

    /**
     * Comma-separated OpenRouter marketplace categories describing the product.
     */
    const val CATEGORIES: String = "cloud-agent,general-chat"

    /**
     * HTTP headers that identify Torvian Chatbot to OpenRouter.
     */
    val headers: Map<String, String> = mapOf(
        "HTTP-Referer" to SITE_URL,
        "X-OpenRouter-Title" to TITLE,
        "X-OpenRouter-Categories" to CATEGORIES
    )
}
