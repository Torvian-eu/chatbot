package eu.torvian.chatbot.server.service.llm

/**
 * Represents common HTTP methods in a generic way, independent of specific HTTP client libraries.
 */
enum class GenericHttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}
