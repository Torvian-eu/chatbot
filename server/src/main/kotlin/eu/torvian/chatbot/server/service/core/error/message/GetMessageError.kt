package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors when retrieving a message by ID.
 */
sealed interface GetMessageError {
    /**
     * Indicates that the message with the specified ID was not found.
     * Maps from MessageError.MessageNotFound in the DAO layer.
     */
    data class MessageNotFound(val id: Long) : GetMessageError
}

fun GetMessageError.toApiError(messageId: Long): ApiError = when (this) {
    is GetMessageError.MessageNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Message not found",
        "messageId" to messageId.toString()
    )
}
