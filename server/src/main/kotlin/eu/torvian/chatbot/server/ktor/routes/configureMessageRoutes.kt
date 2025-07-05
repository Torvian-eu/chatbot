package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.models.UpdateMessageRequest
import eu.torvian.chatbot.server.service.core.MessageService
import eu.torvian.chatbot.server.service.core.error.message.DeleteMessageError
import eu.torvian.chatbot.server.service.core.error.message.UpdateMessageContentError
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.routing.Route

/**
 * Configures routes related to Messages (/api/v1/messages) using Ktor Resources.
 */
fun Route.configureMessageRoutes(messageService: MessageService) {
    // PUT /api/v1/messages/{messageId}/content - Update message content by ID
    put<MessageResource.ById.Content> { resource ->
        val messageId = resource.parent.messageId
        val request = call.receive<UpdateMessageRequest>()
        call.respondEither(
            messageService.updateMessageContent(
                messageId,
                request.content
            )
        ) { error ->
            when (error) {
                is UpdateMessageContentError.MessageNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Message not found", "messageId" to error.id.toString())
            }
        }
    }

    // DELETE /api/v1/messages/{messageId} - Delete message by ID
    delete<MessageResource.ById> { resource ->
        val messageId = resource.messageId
        call.respondEither(
            messageService.deleteMessage(messageId),
            HttpStatusCode.NoContent
        ) { error ->
            when (error) {
                is DeleteMessageError.MessageNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Message not found", "messageId" to error.id.toString())
            }
        }
    }
}