package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when updating the content of a message.
 */
sealed interface UpdateMessageContentError {
    /**
     * Indicates that the message with the specified ID was not found.
     * Maps from MessageError.MessageNotFound in the DAO layer.
     */
    data class MessageNotFound(val id: Long) : UpdateMessageContentError
}

fun UpdateMessageContentError.toApiError(): ApiError = when (this) {
    is UpdateMessageContentError.MessageNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Message not found",
        "messageId" to id.toString()
    )
}
