package eu.torvian.chatbot.server.service.core.error.session

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Errors that can occur when updating the agent role selected for a chat session.
 */
sealed interface UpdateSessionAgentRoleIdError {

    /**
     * Indicates that the session with the specified ID was not found.
     *
     * @property id The missing session identifier.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionAgentRoleIdError

    /**
     * Indicates that the referenced agent role does not exist or is not accessible to the user.
     *
     * @property agentRoleId The missing/inaccessible role identifier.
     */
    data class AgentRoleNotFound(val agentRoleId: Long) : UpdateSessionAgentRoleIdError

    /**
     * Indicates that the referenced agent role is disabled **for the requesting user**, so it cannot
     * be attached to the session. Reaching this error means the role exists and is owned/accessible;
     * only its per-user disabled state blocks the attach.
     *
     * @property agentRoleId The disabled role identifier.
     */
    data class AgentRoleDisabled(val agentRoleId: Long) : UpdateSessionAgentRoleIdError

    /**
     * Indicates that attaching the role would leave the session in an illegal `(agent_role_id,
     * project_id)` pair (the Session Legality Invariant): the session has a selected project the role
     * does not belong to, or the session has no project while the role belongs to one. Reaching this
     * error means the role exists, is owned/accessible and is enabled; only the project-scope
     * mismatch blocks the attach. Removing the role (null) is always legal.
     *
     * @property agentRoleId The role that would violate the invariant.
     * @property projectId The session's current project id; `null` when the session has no project
     *            (and the role belongs to at least one).
     */
    data class AgentRoleNotInProject(
        val agentRoleId: Long,
        val projectId: Long?
    ) : UpdateSessionAgentRoleIdError
}

/**
 * Converts an [UpdateSessionAgentRoleIdError] to its [ApiError] representation.
 */
fun UpdateSessionAgentRoleIdError.toApiError(): ApiError = when (this) {
    is UpdateSessionAgentRoleIdError.SessionNotFound -> apiError(
        CommonApiErrorCodes.NOT_FOUND,
        "Session not found",
        "sessionId" to id.toString()
    )

    is UpdateSessionAgentRoleIdError.AgentRoleNotFound -> apiError(
        CommonApiErrorCodes.INVALID_ARGUMENT,
        "Agent role not found or not accessible",
        "agentRoleId" to agentRoleId.toString()
    )

    is UpdateSessionAgentRoleIdError.AgentRoleDisabled -> apiError(
        CommonApiErrorCodes.CONFLICT,
        "Agent role is disabled",
        "agentRoleId" to agentRoleId.toString()
    )

    is UpdateSessionAgentRoleIdError.AgentRoleNotInProject -> apiError(
        CommonApiErrorCodes.CONFLICT,
        "Agent role does not match the selected project",
        "agentRoleId" to agentRoleId.toString(),
        // The project id is only meaningful when a project is selected; a project-less session makes
        // the mismatch about the role's own project membership instead.
        *if (projectId != null) arrayOf("projectId" to projectId.toString()) else emptyArray()
    )
}
