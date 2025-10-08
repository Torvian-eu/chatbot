package eu.torvian.chatbot.server.service.core.error.model

/**
 * Represents possible errors when adding a new LLM model.
 */
sealed interface AddModelError {
    /**
     * Indicates invalid input data for the model (e.g., name format).
     * This would typically be a business rule validation failure.
     */
    data class InvalidInput(val reason: String) : AddModelError

    /**
     * Indicates that the provider ID referenced by the model does not exist.
     * Maps from InsertModelError.ProviderNotFound in the DAO layer.
     */
    data class ProviderNotFound(val providerId: Long) : AddModelError

    /**
     * Indicates that a model with the specified name already exists.
     * Maps from InsertModelError.ModelNameAlreadyExists in the DAO layer.
     */
    data class ModelNameAlreadyExists(val name: String) : AddModelError

    /**
     * Indicates an ownership-related error when creating the model ownership link.
     * This can represent foreign key violations or ownership conflicts.
     */
    data class OwnershipError(val reason: String) : AddModelError
}
