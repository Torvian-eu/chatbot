package eu.torvian.chatbot.common.api.resources

import eu.torvian.chatbot.common.api.resources.DeleteMode.RECURSIVE
import eu.torvian.chatbot.common.api.resources.DeleteMode.SINGLE
import io.ktor.resources.*

/**
 * Resource definitions for the top-level /api/v1/messages/{messageId} endpoint.
 * (Session-specific messages are defined within SessionResources)
 */
@Resource("messages")
class MessageResource(val parent: Api = Api()) { // References the base Api resource
    /**
     * Resource for a specific message by ID: /api/v1/messages/{messageId}
     *
     * @param parent The parent MessageResource.
     * @param messageId The ID of the message.
     * @param mode The deletion mode for DELETE requests (defaults to SINGLE).
     *             This is a query parameter: ?mode=SINGLE or ?mode=RECURSIVE
     */
    @Resource("{messageId}")
    class ById(
        val parent: MessageResource = MessageResource(),
        val messageId: Long,
        val mode: DeleteMode = SINGLE
    ) {
        /**
         * Resource for updating message content: /api/v1/messages/{messageId}/content
         */
        @Resource("content")
        class Content(val parent: ById)
    }
}

/**
 * Enum representing the mode of deletion for messages.
 *
 * @property SINGLE Delete only the specified message, promoting its children to the parent.
 * @property RECURSIVE Delete the specified message and all its descendants.
 */
enum class DeleteMode {
    SINGLE,
    RECURSIVE
}