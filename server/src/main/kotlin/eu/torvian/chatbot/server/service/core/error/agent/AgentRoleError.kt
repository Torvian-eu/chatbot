package eu.torvian.chatbot.server.service.core.error.agent

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when retrieving an agent role.
 */
sealed interface AgentRoleError {

    /**
     * The requested agent role was not found.
     *
     * @property id The missing role identifier.
     */
    data class NotFound(val id: Long) : AgentRoleError

    /**
     * The requested agent role was not found by name.
     *
     * @property name The missing role name.
     */
    data class NotFoundByName(val name: String) : AgentRoleError
}

/**
 * Converts an [AgentRoleError] to its [ApiError] representation.
 */
fun AgentRoleError.toApiError(): ApiError = when (this) {
    is AgentRoleError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Agent role not found", "roleId" to id.toString())

    is AgentRoleError.NotFoundByName ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Agent role not found", "name" to name)
}
