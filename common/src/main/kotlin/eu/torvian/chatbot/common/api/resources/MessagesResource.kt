package eu.torvian.chatbot.common.api.resources

import io.ktor.resources.*

/**
 * Resource definitions for the top-level /api/v1/messages/{messageId} endpoint.
 * (Session-specific messages are defined within SessionResources)
 */
@Resource("messages")
class MessagesResource(val parent: Api = Api()) { // References the base Api resource
    /**
     * Resource for a specific message by ID: /api/v1/messages/{messageId}
     */
    @Resource("{messageId}")
    class ById(val parent: MessagesResource = MessagesResource(), val messageId: Long) {
        /**
         * Resource for updating message content: /api/v1/messages/{messageId}/content
         */
        @Resource("content")
        class Content(val parent: ById)
    }
}

