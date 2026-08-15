package eu.torvian.chatbot.common.models.api.agent

import kotlinx.serialization.Serializable

/**
 * Request body for selecting or deselecting the agent role of a chat session.
 *
 * A session references an agent role instead of storing its own model/settings/tools; selecting a role
 * simply sets `agent_role_id`, and model/settings/tools are resolved from the role at turn time.
 *
 * @property agentRoleId Identifier of the agent role to attach, or `null` to deselect the role. A
 *            session without a role cannot send messages until a role is selected.
 */
@Serializable
data class UpdateSessionAgentRoleRequest(
    val agentRoleId: Long?
)
