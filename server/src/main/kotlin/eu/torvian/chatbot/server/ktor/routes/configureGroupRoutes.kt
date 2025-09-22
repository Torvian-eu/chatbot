package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.GroupResource
import eu.torvian.chatbot.common.models.CreateGroupRequest
import eu.torvian.chatbot.common.models.RenameGroupRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.GroupService
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Groups (/api/v1/groups) using Ktor Resources.
 */
fun Route.configureGroupRoutes(groupService: GroupService) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/groups - List all groups for authenticated user
        get<GroupResource> {
            val userId = call.getUserId()
            call.respond(groupService.getAllGroups(userId))
        }

        // POST /api/v1/groups - Create a new group
        post<GroupResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateGroupRequest>()
            call.respondEither(groupService.createGroup(userId, request.name), HttpStatusCode.Created) { error ->
                when (error) {
                    is CreateGroupError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name", "reason" to error.reason)
                    is CreateGroupError.OwnershipError ->
                        apiError(CommonApiErrorCodes.INTERNAL, "Failed to set ownership", "reason" to error.reason)
                }
            }
        }

        // DELETE /api/v1/groups/{groupId} - Delete group by ID
        delete<GroupResource.ById> { resource ->
            val userId = call.getUserId()
            val groupId = resource.groupId
            call.respondEither(groupService.deleteGroup(userId, groupId), HttpStatusCode.NoContent) { error ->
                when (error) {
                    is DeleteGroupError.GroupNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to error.id.toString())
                    is DeleteGroupError.AccessDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Access denied", "reason" to error.reason)
                }
            }
        }

        // PUT /api/v1/groups/{groupId} - Rename group by ID
        put<GroupResource.ById> { resource ->
            val userId = call.getUserId()
            val groupId = resource.groupId
            val request = call.receive<RenameGroupRequest>()
            call.respondEither(groupService.renameGroup(userId, groupId, request.name)) { error ->
                when (error) {
                    is RenameGroupError.GroupNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to error.id.toString())
                    is RenameGroupError.InvalidName ->
                        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid group name", "reason" to error.reason)
                    is RenameGroupError.AccessDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Access denied", "reason" to error.reason)
                }
            }
        }
    }
}