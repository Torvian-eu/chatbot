package eu.torvian.chatbot.server.service.core.error.usergroup

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Sealed interface representing errors that can occur when retrieving a group by name.
 */
sealed interface GetGroupByNameError {
    /**
     * Group with the specified name was not found.
     *
     * @property name The name that was not found
     */
    data class NotFound(val name: String) : GetGroupByNameError
}

/**
 * Extension function to convert [GetGroupByNameError] to [ApiError].
 */
fun GetGroupByNameError.toApiError(): ApiError = when (this) {
    is GetGroupByNameError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupName" to name)
}
