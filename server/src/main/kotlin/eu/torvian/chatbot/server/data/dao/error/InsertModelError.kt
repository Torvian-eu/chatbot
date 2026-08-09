package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible errors when inserting a new LLM model.
 */
sealed interface InsertModelError {
    /**
     * Indicates that the provider ID referenced by the model does not exist.
     */
    data class ProviderNotFound(val providerId: Long) : InsertModelError
}
