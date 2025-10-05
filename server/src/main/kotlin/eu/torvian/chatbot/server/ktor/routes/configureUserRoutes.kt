package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.UserResource
import eu.torvian.chatbot.common.models.admin.AssignRoleRequest
import eu.torvian.chatbot.common.models.admin.ChangePasswordRequest
import eu.torvian.chatbot.common.models.admin.UpdatePasswordChangeRequiredRequest
import eu.torvian.chatbot.common.models.admin.UpdateUserRequest
import eu.torvian.chatbot.common.models.admin.UpdateUserStatusRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.UserService
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
 * Configures routes related to User Management (/api/v1/users) using Ktor Resources.
 *
 * All routes require authentication and admin permissions.
 */
fun Route.configureUserRoutes(
    userService: UserService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/users - List all users
        get<UserResource> {
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                userService.getAllUsers()
            }
            call.respondEither(result)
        }

        // GET /api/v1/users/{userId} - Get user by ID
        get<UserResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: UserNotFoundError -> e.toApiError() }) {
                    userService.getUserById(resource.userId).bind()
                }
            }
            call.respondEither(result)
        }

        // GET /api/v1/users/detailed - List all users (with details)
        get<UserResource.UsersDetailed> {
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                userService.getAllUsersWithDetails()
            }
            call.respondEither(result)
        }

        // GET /api/v1/users/{userId}/detailed - Get user by ID (with details)
        get<UserResource.ById.UserDetailed> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: UserNotFoundError -> e.toApiError() }) {
                    userService.getUserWithDetails(resource.parent.userId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/users/{userId} - Update user profile (returns public User)
        put<UserResource.ById> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<UpdateUserRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: UpdateUserError -> e.toApiError() }) {
                    userService.updateUser(resource.userId, request.username, request.email).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/users/{userId}/status - Update user status
        put<UserResource.ById.Status> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<UpdateUserStatusRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: UpdateUserError -> e.toApiError() }) {
                    userService.updateUserStatus(resource.parent.userId, request.status, requestingUserId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/users/{userId}/password-change-required - Update password change required flag
        put<UserResource.ById.PasswordChangeRequired> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<UpdatePasswordChangeRequiredRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: UpdateUserError -> e.toApiError() }) {
                    userService.updatePasswordChangeRequired(resource.parent.userId, request.requiresPasswordChange).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/users/{userId} - Delete user
        delete<UserResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: DeleteUserError -> e.toApiError() }) {
                    userService.deleteUser(resource.userId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // GET /api/v1/users/{userId}/roles - Get user's roles
        get<UserResource.ById.Roles> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                userService.getUserRoles(resource.parent.userId)
            }
            call.respondEither(result)
        }

        // POST /api/v1/users/{userId}/roles - Assign role to user
        post<UserResource.ById.Roles> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<AssignRoleRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: AssignRoleError -> e.toApiError() }) {
                    userService.assignRoleToUser(resource.parent.userId, request.roleId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // DELETE /api/v1/users/{userId}/roles/{roleId} - Revoke role from user
        delete<UserResource.ById.Roles.ByRoleId> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USERS)
                withError({ e: RevokeRoleError -> e.toApiError() }) {
                    userService.revokeRoleFromUser(
                        resource.parent.parent.userId,
                        resource.roleId
                    ).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // PUT /api/v1/users/{userId}/password - Change user password
        put<UserResource.ById.Password> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<ChangePasswordRequest>()

            val result = either {
                // Allow if user is changing their own password OR has MANAGE_USERS permission
                val isOwnPassword = requestingUserId == resource.parent.userId
                val hasManageUsersPermission = authorizationService.hasPermission(
                    requestingUserId,
                    CommonPermissions.MANAGE_USERS
                )

                ensure(isOwnPassword || hasManageUsersPermission) {
                    apiError(
                        CommonApiErrorCodes.PERMISSION_DENIED,
                        "You can only change your own password unless you have MANAGE_USERS permission"
                    )
                }

                withError({ e: ChangePasswordError -> e.toApiError() }) {
                    userService.changePassword(resource.parent.userId, request.newPassword).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}
