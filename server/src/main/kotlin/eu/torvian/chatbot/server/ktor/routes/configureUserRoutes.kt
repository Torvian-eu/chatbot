package eu.torvian.chatbot.server.ktor.routes

import arrow.core.flatMap
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.UserResource
import eu.torvian.chatbot.common.models.admin.*
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
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

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .map { userService.getAllUsers() }
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                }
            }
        }

        // GET /api/v1/users/{userId} - Get user by ID
        get<UserResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .flatMap { userService.getUserById(resource.userId) }
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                    is UserNotFoundError.ById ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to error.id.toString())
                    is UserNotFoundError.ByUsername ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "username" to error.username)
                    else ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error: $error")
                }
            }
        }

        // PUT /api/v1/users/{userId} - Update user profile
        put<UserResource.ById> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<UpdateUserRequest>()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .flatMap {
                        userService.updateUser(resource.userId, request.username, request.email)
                    }
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                    is UpdateUserError.UserNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to error.userId.toString())
                    is UpdateUserError.UsernameAlreadyExists ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Username already exists", "username" to error.username)
                    is UpdateUserError.EmailAlreadyExists ->
                        apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Email already exists", "email" to error.email)
                    is UpdateUserError.InvalidInput ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, error.reason)
                    else ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error: $error")
                }
            }
        }

        // DELETE /api/v1/users/{userId} - Delete user
        delete<UserResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .flatMap { userService.deleteUser(resource.userId) },
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                    is DeleteUserError.UserNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to error.userId.toString())
                    is DeleteUserError.CannotDeleteLastAdmin ->
                        apiError(
                            CommonApiErrorCodes.CONFLICT,
                            "Cannot delete the last administrator",
                            "userId" to error.userId.toString()
                        )
                    else ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error: $error")
                }
            }
        }

        // GET /api/v1/users/{userId}/roles - Get user's roles
        get<UserResource.ById.Roles> { resource ->
            val requestingUserId = call.getUserId()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .map { userService.getUserRoles(resource.parent.userId) }
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                }
            }
        }

        // POST /api/v1/users/{userId}/roles - Assign role to user
        post<UserResource.ById.Roles> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<AssignRoleRequest>()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .flatMap {
                        userService.assignRoleToUser(resource.parent.userId, request.roleId)
                    },
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                    is AssignRoleError.UserOrRoleNotFound ->
                        apiError(
                            CommonApiErrorCodes.NOT_FOUND,
                            "User or role not found",
                            "userId" to error.userId.toString(),
                            "roleId" to error.roleId.toString()
                        )
                    is AssignRoleError.RoleAlreadyAssigned ->
                        apiError(
                            CommonApiErrorCodes.CONFLICT,
                            "Role already assigned to user",
                            "userId" to error.userId.toString(),
                            "roleId" to error.roleId.toString()
                        )
                    else ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error: $error")
                }
            }
        }

        // DELETE /api/v1/users/{userId}/roles/{roleId} - Revoke role from user
        delete<UserResource.ById.Roles.ByRoleId> { resource ->
            val requestingUserId = call.getUserId()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .flatMap {
                        userService.revokeRoleFromUser(
                            resource.parent.parent.userId,
                            resource.roleId
                        )
                    },
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                    is RevokeRoleError.RoleNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Role not found", "roleId" to error.roleId.toString())
                    is RevokeRoleError.RoleNotAssigned ->
                        apiError(
                            CommonApiErrorCodes.NOT_FOUND,
                            "Role not assigned to user",
                            "userId" to error.userId.toString(),
                            "roleId" to error.roleId.toString()
                        )
                    is RevokeRoleError.CannotRevokeLastAdminRole ->
                        apiError(
                            CommonApiErrorCodes.CONFLICT,
                            "Cannot revoke admin role from the last administrator",
                            "userId" to error.userId.toString()
                        )
                    else ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error: $error")
                }
            }
        }

        // PUT /api/v1/users/{userId}/password - Change user password
        put<UserResource.ById.Password> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<ChangePasswordRequest>()

            call.respondEither(
                authorizationService.requirePermission(requestingUserId, "manage", "users")
                    .flatMap {
                        userService.changePassword(resource.parent.userId, request.newPassword)
                    },
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is AuthorizationError.PermissionDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Admin access required")
                    is ChangePasswordError.UserNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "User not found", "userId" to error.userId.toString())
                    is ChangePasswordError.InvalidPassword ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, error.reason)
                    else ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error: $error")
                }
            }
        }
    }
}
