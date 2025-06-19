package eu.torvian.chatbot.server.service.core.error.model

/**
 * Represents possible errors when retrieving an LLM model by ID.
 */
sealed interface GetModelError {
    /**
     * Indicates that the model with the specified ID was not found.
     * Maps from ModelError.ModelNotFound in the DAO layer.
     */
    data class ModelNotFound(val id: Long) : GetModelError
}
