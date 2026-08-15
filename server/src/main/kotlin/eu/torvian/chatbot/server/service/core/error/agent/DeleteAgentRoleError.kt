package eu.torvian.chatbot.server.service.core.error.agent

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when deleting an agent role.
 *
 * Deleting a role is intentionally non-destructive for sessions: `chat_sessions.agent_role_id` and
 * `assistant_messages.agent_role_id` use `ON DELETE SET NULL`, so affected sessions become inert until a
 * role is re-selected. No "role in use" rejection is therefore needed.
 */
sealed interface DeleteAgentRoleError {

    /**
     * The agent role to delete was not found.
     *
     * @property id The missing role identifier.
     */
    data class NotFound(val id: Long) : DeleteAgentRoleError
}

/**
 * Converts a [DeleteAgentRoleError] to its [ApiError] representation.
 */
fun DeleteAgentRoleError.toApiError(): ApiError = when (this) {
    is DeleteAgentRoleError.NotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Agent role not found", "roleId" to id.toString())
}
