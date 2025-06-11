package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during LLMModel data operations.
 */
sealed interface ModelError {
    /**
     * Indicates that a model with the specified ID was not found.
     */
    data class ModelNotFound(val id: Long) : ModelError
}
