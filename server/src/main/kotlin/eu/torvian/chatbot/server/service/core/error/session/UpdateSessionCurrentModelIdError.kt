package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when updating a chat session's current model ID.
 */
sealed interface UpdateSessionCurrentModelIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionCurrentModelIdError

    /**
     * Indicates that the provided model ID was not found.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionCurrentModelIdError

    /**
     * Indicates that the model exists but doesn't have the required CHAT type.
     */
    data class InvalidModelType(val modelId: Long, val actualType: String) : UpdateSessionCurrentModelIdError

    /**
     * Indicates that the model exists but is deprecated (not active).
     */
    data class DeprecatedModel(val modelId: Long) : UpdateSessionCurrentModelIdError
}

fun UpdateSessionCurrentModelIdError.toApiError(): ApiError = when (this) {
    is UpdateSessionCurrentModelIdError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionCurrentModelIdError.InvalidRelatedEntity -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid model ID provided",
        "modelId" to message // message likely contains details; keep backward compatible
    )

    is UpdateSessionCurrentModelIdError.InvalidModelType -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Model type must be CHAT",
        "modelId" to modelId.toString(),
        "actualType" to actualType
    )

    is UpdateSessionCurrentModelIdError.DeprecatedModel -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Model is deprecated and cannot be used",
        "modelId" to modelId.toString()
    )
}
