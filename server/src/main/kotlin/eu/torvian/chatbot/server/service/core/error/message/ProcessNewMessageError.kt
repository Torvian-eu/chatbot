package eu.torvian.chatbot.server.service.core.error.message

/**
 * Represents possible errors during the process of receiving and responding to a new user message.
 */
sealed interface ProcessNewMessageError {
    /**
     * Indicates that the session the message belongs to was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val sessionId: Long) : ProcessNewMessageError
    /**
     * Indicates that the parent message does not belong to the specified session (business logic validation).
     */
    data class ParentNotInSession(val sessionId: Long, val parentId: Long) : ProcessNewMessageError
    /**
     * Indicates that the session's current model or settings are not configured correctly or not found.
     * Maps from ModelError.ModelNotFound, SettingsError.SettingsNotFound, or business logic check.
     */
    data class ModelConfigurationError(val message: String) : ProcessNewMessageError
    /**
     * Indicates a failure occurred when calling the external LLM API.
     * Wraps the external service error details.
     * Maps from exceptions or specific error responses from LLMApiClient.
     */
    data class ExternalServiceError(val message: String) : ProcessNewMessageError
}
