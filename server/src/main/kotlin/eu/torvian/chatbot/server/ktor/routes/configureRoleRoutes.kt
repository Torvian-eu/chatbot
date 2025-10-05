package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.resources.RoleResource
import eu.torvian.chatbot.common.models.api.admin.CreateRoleRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateRoleRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.RoleService
import eu.torvian.chatbot.server.service.core.error.auth.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.routing.*

/**
 * Configures routes related to Role Management (/api/v1/roles) using Ktor Resources.
 *
 * All routes require authentication and MANAGE_ROLES permission. This provides
 * fine-grained access control separate from user management permissions.
 *
 * Available endpoints:
 * - GET /api/v1/roles - List all available roles
 * - POST /api/v1/roles - Create new role (admin only)
 * - GET /api/v1/roles/{roleId} - Get specific role by ID
 * - PUT /api/v1/roles/{roleId} - Update role (admin only)
 * - DELETE /api/v1/roles/{roleId} - Delete role (admin only)
 */
fun Route.configureRoleRoutes(
    roleService: RoleService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/roles - List all available roles
        get<RoleResource> {
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_ROLES)
                roleService.getAllRoles()
            }
            call.respondEither(result)
        }

        // GET /api/v1/roles/{roleId} - Get role by ID
        get<RoleResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_ROLES)
                withError({ e: RoleNotFoundError.ById -> e.toApiError() }) {
                    roleService.getRoleById(resource.roleId).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/roles - Create new role
        post<RoleResource> {
            val requestingUserId = call.getUserId()
            val request = call.receive<CreateRoleRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_ROLES)
                withError({ e: CreateRoleError -> e.toApiError() }) {
                    roleService.createRole(request.name, request.description).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // PUT /api/v1/roles/{roleId} - Update role
        put<RoleResource.ById> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<UpdateRoleRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_ROLES)
                withError({ e: UpdateRoleError -> e.toApiError() }) {
                    roleService.updateRole(resource.roleId, request.name, request.description).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/roles/{roleId} - Delete role
        delete<RoleResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_ROLES)
                withError({ e: DeleteRoleError -> e.toApiError() }) {
                    roleService.deleteRole(resource.roleId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}
