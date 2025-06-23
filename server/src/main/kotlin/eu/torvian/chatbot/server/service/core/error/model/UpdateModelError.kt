package eu.torvian.chatbot.server.service.core.error.model

/**
 * Represents possible errors when updating an existing LLM model.
 */
sealed interface UpdateModelError {
    /**
     * Indicates that the model with the specified ID was not found.
     * Maps from UpdateModelError.ModelNotFound in the DAO layer.
     */
    data class ModelNotFound(val id: Long) : UpdateModelError

    /**
     * Indicates invalid input data for the update (e.g., name format).
     * This would typically be a business rule validation failure.
     */
    data class InvalidInput(val reason: String) : UpdateModelError

    /**
     * Indicates that the provider ID referenced by the model does not exist.
     * Maps from UpdateModelError.ProviderNotFound in the DAO layer.
     */
    data class ProviderNotFound(val providerId: Long) : UpdateModelError

    /**
     * Indicates that a model with the specified name already exists (and it's not the same model being updated).
     * Maps from UpdateModelError.ModelNameAlreadyExists in the DAO layer.
     */
    data class ModelNameAlreadyExists(val name: String) : UpdateModelError
}
