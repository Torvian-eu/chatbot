package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for role management endpoints.
 *
 * This resource defines the URL structure for role CRUD operations:
 * - GET /api/v1/roles - List all roles
 * - POST /api/v1/roles - Create new role
 * - GET /api/v1/roles/{roleId} - Get specific role
 * - PUT /api/v1/roles/{roleId} - Update specific role
 * - DELETE /api/v1/roles/{roleId} - Delete specific role
 */
@Resource("roles")
class RoleResource(val parent: Api = Api()) {
    /**
     * Resource for operations on a specific role by ID.
     *
     * @property parent The parent RoleResource
     * @property roleId The unique identifier of the role
     */
    @Resource("{roleId}")
    class ById(val parent: RoleResource = RoleResource(), val roleId: Long)
}
