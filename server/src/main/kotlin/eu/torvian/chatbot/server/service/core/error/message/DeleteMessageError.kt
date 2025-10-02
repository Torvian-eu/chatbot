package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when deleting a message.
 */
sealed interface DeleteMessageError {
    /**
     * Indicates that the message with the specified ID was not found.
     * Maps from MessageError.MessageNotFound in the DAO layer.
     */
    data class MessageNotFound(val id: Long) : DeleteMessageError

    /**
     * Indicates that updating the session's leaf message ID failed after successful message deletion.
     * This ensures the session state remains consistent even if the deletion succeeded.
     */
    data class SessionUpdateFailed(val sessionId: Long) : DeleteMessageError
}

fun DeleteMessageError.toApiError(): ApiError = when (this) {
    is DeleteMessageError.MessageNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Message not found",
        "messageId" to id.toString()
    )

    is DeleteMessageError.SessionUpdateFailed -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Failed to update session after message deletion",
        "sessionId" to sessionId.toString()
    )
}
