package eu.torvian.chatbot.server.service.llm

/**
 * Represents a content type in a generic way, independent of specific HTTP client libraries.
 * Primarily stores the MIME type string.
 * 
 * @property contentType The MIME type string (e.g., "application/json")
 */
data class GenericContentType(val contentType: String) {
    companion object {
        val APPLICATION_JSON = GenericContentType("application/json")
        val TEXT_PLAIN = GenericContentType("text/plain")
        // Add other common types as needed
    }
}
