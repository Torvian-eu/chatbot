package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.GroupResource
import eu.torvian.chatbot.common.models.CreateGroupRequest
import eu.torvian.chatbot.common.models.RenameGroupRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.authorizer.AccessMode
import eu.torvian.chatbot.server.service.core.GroupService
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.Long
import kotlin.Unit
import kotlin.let
import kotlin.to

/**
 * Configures routes related to Groups (/api/v1/groups) using Ktor Resources.
 */
fun Route.configureGroupRoutes(groupService: GroupService, authorizationService: AuthorizationService) {
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

            either {
                requireGroupWriteAccess(authorizationService, userId, groupId)
                withError({ deleteError: DeleteGroupError ->
                    when (deleteError) {
                        is DeleteGroupError.GroupNotFound ->
                            apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to groupId.toString())
                    }
                }) {
                    groupService.deleteGroup(groupId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.NoContent)
            }
        }

        // PUT /api/v1/groups/{groupId} - Rename group by ID
        put<GroupResource.ById> { resource ->
            val userId = call.getUserId()
            val groupId = resource.groupId
            val request = call.receive<RenameGroupRequest>()

            either {
                requireGroupWriteAccess(authorizationService, userId, groupId)
                withError({ renameError: RenameGroupError ->
                    when (renameError) {
                        is RenameGroupError.GroupNotFound ->
                            apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to groupId.toString())

                        is RenameGroupError.InvalidName ->
                            apiError(
                                CommonApiErrorCodes.INVALID_ARGUMENT,
                                "Invalid group name",
                                "reason" to renameError.reason
                            )
                    }
                }) {
                    groupService.renameGroup(groupId, request.name).bind()
                }
            }.let { result ->
                call.respondEither(result)
            }
        }
    }
}

private suspend inline fun Raise<ApiError>.requireGroupWriteAccess(
    authorizationService: AuthorizationService,
    userId: Long,
    groupId: Long
): Unit =
    withError({ authError: ResourceAuthorizationError ->
        when (authError) {
            is ResourceAuthorizationError.ResourceNotFound ->
                apiError(CommonApiErrorCodes.NOT_FOUND, "Group not found", "groupId" to groupId.toString())

            is ResourceAuthorizationError.PermissionDenied ->
                apiError(
                    CommonApiErrorCodes.PERMISSION_DENIED,
                    "Access denied",
                    "userId" to userId.toString(),
                    "groupId" to groupId.toString(),
                    "reason" to authError.reason
                )
        }
    }) {
        authorizationService.requireAccess(userId, "group", groupId, AccessMode.WRITE).bind()
    }
