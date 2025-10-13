package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.resources.UserGroupResource
import eu.torvian.chatbot.common.models.api.admin.AddUserToGroupRequest
import eu.torvian.chatbot.common.models.api.admin.CreateUserGroupRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserGroupRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.error.usergroup.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.*

/**
 * Configures routes related to User Group Management [UserGroupResource] using Ktor Resources.
 *
 * All routes require authentication and [CommonPermissions.MANAGE_USER_GROUPS] permission.
 */
fun Route.configureUserGroupRoutes(
    userGroupService: UserGroupService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/user-groups - List all user groups
        get<UserGroupResource> {
//            val requestingUserId = call.getUserId()
//
//            val result = either {
//                // TODO: Make user group a shareable resource, and allow users to see groups for which they have read access
//                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
//                userGroupService.getAllGroups()
//            }
//            call.respondEither(result)
            call.respond(userGroupService.getAllGroups()) // currently no permission needed to list groups
        }

        // POST /api/v1/user-groups - Create new user group
        post<UserGroupResource> {
            val requestingUserId = call.getUserId()
            val request = call.receive<CreateUserGroupRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
                withError({ e: CreateGroupError -> e.toApiError() }) {
                    userGroupService.createGroup(
                        name = request.name,
                        description = request.description
                    ).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // GET /api/v1/user-groups/{groupId} - Get user group by ID
        get<UserGroupResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
                withError({ e: GetGroupByIdError -> e.toApiError() }) {
                    userGroupService.getGroupById(resource.groupId).bind()
                }
            }
            call.respondEither(result)
        }

        // PUT /api/v1/user-groups/{groupId} - Update user group
        put<UserGroupResource.ById> { resource ->
            val requestingUserId = call.getUserId()
            val request = call.receive<UpdateUserGroupRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
                withError({ e: UpdateGroupError -> e.toApiError() }) {
                    userGroupService.updateGroup(
                        groupId = resource.groupId,
                        name = request.name,
                        description = request.description
                    ).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/user-groups/{groupId} - Delete user group
        delete<UserGroupResource.ById> { resource ->
            val requestingUserId = call.getUserId()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
                withError({ e: DeleteGroupError -> e.toApiError() }) {
                    userGroupService.deleteGroup(resource.groupId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // GET /api/v1/user-groups/{groupId}/members - List group members
        get<UserGroupResource.ById.Members> { resource ->
            val requestingUserId = call.getUserId()
            val groupId = resource.parent.groupId

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)

                // First verify the group exists
                withError({ e: GetGroupByIdError -> e.toApiError() }) {
                    userGroupService.getGroupById(groupId).bind()
                }

                // Get all users in the group
                userGroupService.getUsersInGroup(groupId)
            }
            call.respondEither(result)
        }

        // POST /api/v1/user-groups/{groupId}/members - Add user to group
        post<UserGroupResource.ById.Members> { resource ->
            val requestingUserId = call.getUserId()
            val groupId = resource.parent.groupId
            val request = call.receive<AddUserToGroupRequest>()

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
                withError({ e: AddUserToGroupError -> e.toApiError() }) {
                    userGroupService.addUserToGroup(
                        userId = request.userId,
                        groupId = groupId
                    ).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // DELETE /api/v1/user-groups/{groupId}/members/{userId} - Remove user from group
        delete<UserGroupResource.ById.Members.ByUserId> { resource ->
            val requestingUserId = call.getUserId()
            val groupId = resource.parent.parent.groupId
            val userId = resource.userId

            val result = either {
                requirePermission(authorizationService, requestingUserId, CommonPermissions.MANAGE_USER_GROUPS)
                withError({ e: RemoveUserFromGroupError -> e.toApiError() }) {
                    userGroupService.removeUserFromGroup(
                        userId = userId,
                        groupId = groupId
                    ).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}