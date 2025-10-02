package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when assigning or unassigning a session to/from a group.
 */
sealed interface UpdateSessionGroupIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionGroupIdError
    /**
     * Indicates that the target group with the specified ID was not found when assigning.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionGroupIdError
}

fun UpdateSessionGroupIdError.toApiError(): ApiError = when (this) {
    is UpdateSessionGroupIdError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionGroupIdError.InvalidRelatedEntity -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid group ID provided",
        "groupId" to message
    )
}
