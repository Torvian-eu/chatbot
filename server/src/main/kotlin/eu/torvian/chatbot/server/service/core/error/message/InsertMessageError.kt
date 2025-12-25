package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.data.dao.error.SessionError
import eu.torvian.chatbot.server.data.dao.error.InsertMessageError as DaoInsertMessageError

sealed interface InsertMessageError {
    data class TargetNotFound(val id: Long) : InsertMessageError
    data class SessionNotFound(val id: Long) : InsertMessageError
    data class InvalidOperation(val message: String) : InsertMessageError
}

fun InsertMessageError.toApiError(): ApiError = when (this) {
    is InsertMessageError.TargetNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Target message with ID $id not found."
    )
    is InsertMessageError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session with ID $id not found."
    )
    is InsertMessageError.InvalidOperation -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        message
    )
}

fun DaoInsertMessageError.toServiceError(): InsertMessageError = when (this) {
    is DaoInsertMessageError.ParentNotFound -> InsertMessageError.TargetNotFound(parentId)
    is DaoInsertMessageError.SessionNotFound -> InsertMessageError.SessionNotFound(sessionId)
    is DaoInsertMessageError.ParentNotInSession -> InsertMessageError.InvalidOperation("Target message $parentId is not in session $sessionId")
    is DaoInsertMessageError.ChildIsParent -> InsertMessageError.InvalidOperation("Child $childId cannot be parent of $parentId")
    is DaoInsertMessageError.ChildAlreadyExists -> InsertMessageError.InvalidOperation("Child $childId already exists in parent $parentId")
}

fun SessionError.toServiceError(): InsertMessageError = when (this) {
    is SessionError.SessionNotFound -> InsertMessageError.SessionNotFound(id)
    is SessionError.ForeignKeyViolation -> InsertMessageError.InvalidOperation(message)
}

