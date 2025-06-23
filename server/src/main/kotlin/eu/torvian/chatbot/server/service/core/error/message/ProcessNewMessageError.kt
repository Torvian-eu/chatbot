package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.server.service.llm.LLMCompletionError

/**
 * Represents possible errors during the process of receiving and responding to a new user message.
 */
sealed interface ProcessNewMessageError {
    /**
     * Indicates that the session the message belongs to was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     *
     * @property sessionId The ID of the session that was not found
     */
    data class SessionNotFound(val sessionId: Long) : ProcessNewMessageError

    /**
     * Indicates that the parent message does not belong to the specified session (business logic validation).
     *
     * @property sessionId The ID of the session
     * @property parentId The ID of the parent message that doesn't belong to the session
     */
    data class ParentNotInSession(val sessionId: Long, val parentId: Long) : ProcessNewMessageError

    /**
     * Indicates that the session's current model or settings are not configured correctly or not found.
     * Maps from ModelError.ModelNotFound, SettingsError.SettingsNotFound, or business logic check.
     *
     * @property message Descriptive error message about the configuration issue
     */
    data class ModelConfigurationError(val message: String) : ProcessNewMessageError

    /**
     * Indicates a failure occurred when calling the external LLM API.
     * Wraps the external service error details.
     * Maps from exceptions or specific error responses from LLMApiClient.
     *
     * @property llmError The specific LLM error that occurred
     */
    data class ExternalServiceError(val llmError: LLMCompletionError) : ProcessNewMessageError // Holds the specific LLM error
}
