package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Represents possible errors when validating a new message before processing.
 */
sealed interface ValidateNewMessageError {
    /**
     * Indicates that the session the message belongs to was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     *
     * @property sessionId The ID of the session that was not found
     */
    data class SessionNotFound(val sessionId: Long) : ValidateNewMessageError

    /**
     * Indicates that the parent message does not belong to the specified session (business logic validation).
     *
     * @property sessionId The ID of the session
     * @property parentId The ID of the parent message that doesn't belong to the session
     */
    data class ParentNotInSession(val sessionId: Long, val parentId: Long) : ValidateNewMessageError

    /**
     * Indicates that the session's current model or settings are not configured correctly or not found.
     * Maps from ModelError.ModelNotFound, SettingsError.SettingsNotFound, or business logic check.
     *
     * @property message Descriptive error message about the configuration issue
     */
    data class ModelConfigurationError(val message: String) : ValidateNewMessageError
}

/**
 * Extension function to convert ValidateNewMessageError to ApiError for HTTP responses.
 */
fun ValidateNewMessageError.toApiError(): ApiError = when (this) {
    is ValidateNewMessageError.SessionNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Session not found", "sessionId" to sessionId.toString())

    is ValidateNewMessageError.ParentNotInSession ->
        apiError(
            CommonApiErrorCodes.INVALID_ARGUMENT,
            "Parent message does not belong to this session",
            "sessionId" to sessionId.toString(),
            "parentId" to parentId.toString()
        )

    is ValidateNewMessageError.ModelConfigurationError ->
        apiError(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR, "LLM configuration error", "details" to message)
}
