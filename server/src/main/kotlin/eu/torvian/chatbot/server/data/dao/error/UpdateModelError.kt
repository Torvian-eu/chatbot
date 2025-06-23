package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible errors when updating an existing LLM model.
 */
sealed interface UpdateModelError {
    /**
     * Indicates that the model with the specified ID was not found.
     */
    data class ModelNotFound(val id: Long) : UpdateModelError
    
    /**
     * Indicates that the provider ID referenced by the model does not exist.
     */
    data class ProviderNotFound(val providerId: Long) : UpdateModelError
    
    /**
     * Indicates that a model with the specified name already exists (and it's not the same model being updated).
     */
    data class ModelNameAlreadyExists(val name: String) : UpdateModelError
}
