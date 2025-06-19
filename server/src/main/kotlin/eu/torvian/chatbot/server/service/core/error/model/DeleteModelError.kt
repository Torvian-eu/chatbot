package eu.torvian.chatbot.server.service.core.error.model

/**
 * Represents possible errors when deleting an LLM model.
 */
sealed interface DeleteModelError {
    /**
     * Indicates that the model with the specified ID was not found.
     * Maps from ModelError.ModelNotFound in the DAO layer.
     */
    data class ModelNotFound(val id: Long) : DeleteModelError
}
