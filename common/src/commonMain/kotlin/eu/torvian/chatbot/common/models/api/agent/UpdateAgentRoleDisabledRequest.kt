package eu.torvian.chatbot.common.models.api.agent

import kotlinx.serialization.Serializable

/**
 * Request body for toggling the current user's disabled state of an agent role.
 *
 * Corresponds to `PUT /api/v1/agent-roles/{roleId}/disabled`. The state is **per requesting user**:
 * a `true` value records a `(user, role)` row in the `agent_role_disabled` side table (role disabled
 * for that user), and `false` removes it (role enabled for that user again). Create/update role
 * request DTOs deliberately carry no `disabled` field; this dedicated endpoint is the only surface
 * that changes the flag.
 *
 * @property disabled The new disabled state for the requesting user: `true` disables the role for
 *            that user, `false` re-enables it. The operation is idempotent.
 */
@Serializable
data class UpdateAgentRoleDisabledRequest(
    val disabled: Boolean
)