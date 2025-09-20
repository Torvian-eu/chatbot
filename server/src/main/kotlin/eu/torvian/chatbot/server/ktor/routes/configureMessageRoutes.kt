package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
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
    messageService: MessageService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // PUT /api/v1/messages/{messageId}/content - Update message content by ID
        put<MessageResource.ById.Content> { resource ->
            val userId = call.getUserId()
            val messageId = resource.parent.messageId
            val request = call.receive<UpdateMessageRequest>()

            call.respondEither(
                messageService.updateMessageContent(userId, messageId, request.content)
            ) { error ->
                when (error) {
                    is UpdateMessageContentError.MessageNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Message not found", "messageId" to error.id.toString())

                    is UpdateMessageContentError.AccessDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Access denied", "reason" to error.reason)
                }
            }
        }

        // DELETE /api/v1/messages/{messageId} - Delete message by ID
        // Optional query parameter mode=single to perform non-recursive single delete(default), and mode=recursive to delete children.
        delete<MessageResource.ById> { resource ->
            val userId = call.getUserId()
            val messageId = resource.messageId
            val mode = call.request.queryParameters["mode"]

            val result = if (mode == "recursive") {
                messageService.deleteMessageRecursively(userId, messageId)
            } else {
                messageService.deleteMessage(userId, messageId)
            }

            call.respondEither(
                result,
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteMessageError.MessageNotFound ->
                        apiError(CommonApiErrorCodes.NOT_FOUND, "Message not found", "messageId" to error.id.toString())

                    is DeleteMessageError.AccessDenied ->
                        apiError(CommonApiErrorCodes.PERMISSION_DENIED, "Access denied", "reason" to error.reason)

                    is DeleteMessageError.SessionUpdateFailed ->
                        apiError(
                            CommonApiErrorCodes.INTERNAL,
                            "Failed to update session after message deletion",
                            "sessionId" to error.sessionId.toString()
                        )
                }
            }
        }
    } // End authenticate block
}