package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents errors specific to the removeChildFromMessage operation in MessageDao.
 */
sealed interface MessageRemoveChildError {
    /**
     * Indicates that the parent message with the specified ID was not found.
     */
    data class ParentNotFound(val parentId: Long) : MessageRemoveChildError

    /**
     * Indicates that the child message with the specified ID was not found in the parent's children list.
     */
    data class ChildNotFound(val parentId: Long, val childId: Long) : MessageRemoveChildError
}
