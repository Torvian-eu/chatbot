package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.GetMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
import eu.torvian.chatbot.server.service.core.error.message.toApiError
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.service.security.authorizer.AccessMode
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import eu.torvian.chatbot.server.service.security.error.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Messages (/api/v1/messages) using Ktor Resources.
 * All routes are protected and require user authentication with proper ownership validation.
 */
fun Route.configureMessageRoutes(
    messageService: MessageService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // PUT /api/v1/messages/{messageId}/content - Update message content by ID
        put<MessageResource.ById.Content> { resource ->
            val userId = call.getUserId()
            val messageId = resource.parent.messageId
            val request = call.receive<UpdateMessageRequest>()

            val result = either {
                requireMessageAccess(messageService, authorizationService, userId, messageId, AccessMode.WRITE)
                withError({ error: UpdateMessageContentError -> error.toApiError() }) {
                    messageService.updateMessageContent(messageId, request.content).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/messages/{messageId} - Delete message by ID
        // Optional query parameter mode=single to perform non-recursive single delete(default), and mode=recursive to delete children.
        delete<MessageResource.ById> { resource ->
            val userId = call.getUserId()
            val messageId = resource.messageId
            val mode = call.request.queryParameters["mode"]

            val result = either {
                requireMessageAccess(messageService, authorizationService, userId, messageId, AccessMode.WRITE)
                withError({ error: DeleteMessageError -> error.toApiError() }) {
                    if (mode == "recursive") {
                        messageService.deleteMessageRecursively(messageId).bind()
                    } else {
                        messageService.deleteMessage(messageId).bind()
                    }
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    } // End authenticate block
}

private suspend inline fun Raise<ApiError>.requireMessageAccess(
    messageService: MessageService,
    authorizationService: AuthorizationService,
    userId: Long,
    messageId: Long,
    accessMode: AccessMode
) {
    val sessionId = withError({ error: GetMessageError -> error.toApiError(messageId) }) {
        messageService.getMessageById(messageId).bind().sessionId
    }
    withError({ rae: ResourceAuthorizationError -> rae.toApiError() }) {
        authorizationService.requireAccess(userId, ResourceType.SESSION, sessionId, accessMode).bind()
    }
}