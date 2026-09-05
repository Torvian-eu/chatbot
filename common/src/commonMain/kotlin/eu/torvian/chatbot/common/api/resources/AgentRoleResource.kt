package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for user-defined agent role endpoints.
 *
 * This resource defines the URL structure for agent-role CRUD operations:
 * - GET /api/v1/agent-roles - List roles accessible to the user
 * - POST /api/v1/agent-roles - Create a new role
 * - GET /api/v1/agent-roles/{roleId} - Get a specific role (with resolved instructions)
 * - PUT /api/v1/agent-roles/{roleId} - Update a specific role
 * - DELETE /api/v1/agent-roles/{roleId} - Delete a specific role
 * - PUT /api/v1/agent-roles/{roleId}/disabled - Set the disabled state of a role for the current user
 */
@Resource("agent-roles")
class AgentRoleResource(val parent: Api = Api()) {
    /**
     * Resource for operations on a specific agent role by ID.
     *
     * @property parent The parent [AgentRoleResource].
     * @property roleId The unique identifier of the agent role.
     */
    @Resource("{roleId}")
    class ById(val parent: AgentRoleResource = AgentRoleResource(), val roleId: Long) {
        /**
         * Resource for toggling the current user's disabled state for a role:
         * PUT /api/v1/agent-roles/{roleId}/disabled. The body carries the new state
         * ([eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleDisabledRequest]); the path
         * itself holds no value.
         *
         * @property parent The parent [ById] resource carrying the role id.
         */
        @Resource("disabled")
        class Disabled(val parent: ById)
    }
}
