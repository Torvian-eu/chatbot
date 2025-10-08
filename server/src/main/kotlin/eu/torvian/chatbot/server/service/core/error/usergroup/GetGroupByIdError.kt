package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when retrieving a group by ID.
 */
sealed interface GetGroupByIdError {
    /**
     * Group with the specified ID was not found.
     *
     * @property id The ID that was not found
     */
    data class NotFound(val id: Long) : GetGroupByIdError
}

/**
 * Extension function to convert [GetGroupByIdError] to [ApiError].
 */
fun GetGroupByIdError.toApiError(): ApiError = when (this) {
    is GetGroupByIdError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to id.toString())
}
