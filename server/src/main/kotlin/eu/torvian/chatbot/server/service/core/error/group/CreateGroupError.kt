package eu.torvian.chatbot.server.service.core.error.group

import eu.torvian.chatbot.common.api.*

/**
 * Represents possible errors during the creation of a chat group.
 */
sealed interface CreateGroupError {
    /**
     * Indicates that the provided name is invalid (e.g., blank).
     */
    data class InvalidName(val reason: String) : CreateGroupError

    /**
     * Indicates that there was an error setting ownership for the group.
     */
    data class OwnershipError(val reason: String) : CreateGroupError
}

fun CreateGroupError.toApiError(): ApiError = when (this) {
    is CreateGroupError.InvalidName -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Invalid group name",
        "reason" to reason
    )

    is CreateGroupError.OwnershipError -> apiError(
        CommonApiErrorCodes.INTERNAL,
        "Failed to set ownership",
        "reason" to reason
    )
}
