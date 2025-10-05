package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.resources.GroupResource
import eu.torvian.chatbot.common.models.api.core.CreateGroupRequest
import eu.torvian.chatbot.common.models.api.core.RenameGroupRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.GroupService
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError
import eu.torvian.chatbot.server.service.core.error.group.toApiError
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.service.security.authorizer.AccessMode
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import eu.torvian.chatbot.server.service.security.error.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

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

            val result = either {
                withError({ e: CreateGroupError -> e.toApiError() }) {
                    groupService.createGroup(userId, request.name).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // DELETE /api/v1/groups/{groupId} - Delete group by ID
        delete<GroupResource.ById> { resource ->
            val userId = call.getUserId()
            val groupId = resource.groupId

            either {
                requireGroupAccess(authorizationService, userId, groupId, AccessMode.WRITE)
                withError({ deleteError: DeleteGroupError -> deleteError.toApiError() }) {
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
                requireGroupAccess(authorizationService, userId, groupId, AccessMode.WRITE)
                withError({ renameError: RenameGroupError -> renameError.toApiError() }) {
                    groupService.renameGroup(groupId, request.name).bind()
                }
            }.let { result ->
                call.respondEither(result)
            }
        }
    }
}

private suspend inline fun Raise<ApiError>.requireGroupAccess(
    authorizationService: AuthorizationService,
    userId: Long,
    groupId: Long,
    accessMode: AccessMode
): Unit =
    withError({ authError: ResourceAuthorizationError -> authError.toApiError() }) {
        authorizationService.requireAccess(userId, ResourceType.GROUP, groupId, accessMode).bind()
    }
