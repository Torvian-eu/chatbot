package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when updating a chat session's current settings ID.
 */
sealed interface UpdateSessionCurrentSettingsIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionCurrentSettingsIdError
    /**
     * Indicates that the provided settings ID was not found.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionCurrentSettingsIdError
    /**
     * Indicates that the provided settings are not compatible with the session's current model.
     * This occurs when trying to assign settings that belong to a different model.
     */
    data class SettingsModelMismatch(val settingsId: Long, val settingsModelId: Long, val sessionModelId: Long?) : UpdateSessionCurrentSettingsIdError
    /**
     * Indicates that the provided settings are not of the ChatModelSettings type.
     * This occurs when trying to assign settings that are not suitable for chat sessions.
     */
    data class InvalidSettingsType(val settingsId: Long, val actualType: String) : UpdateSessionCurrentSettingsIdError
}

fun UpdateSessionCurrentSettingsIdError.toApiError(): ApiError = when (this) {
    is UpdateSessionCurrentSettingsIdError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionCurrentSettingsIdError.InvalidRelatedEntity -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid settings ID provided",
        "details" to message
    )

    is UpdateSessionCurrentSettingsIdError.SettingsModelMismatch -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Settings are not compatible with the current model",
        "settingsId" to settingsId.toString(),
        "settingsModelId" to settingsModelId.toString(),
        "sessionModelId" to (sessionModelId?.toString() ?: "null")
    )

    is UpdateSessionCurrentSettingsIdError.InvalidSettingsType -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Settings must be of ChatModelSettings type for chat sessions",
        "settingsId" to settingsId.toString(),
        "actualType" to actualType
    )
}
