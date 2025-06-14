package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents errors specific to the addChildToMessage operation in MessageDao.
 */
sealed interface MessageAddChildError {
    /**
     * Indicates that the parent message with the specified ID was not found.
     */
    data class ParentNotFound(val parentId: Long) : MessageAddChildError

    /**
     * Indicates that the child message already exists in the parent's children list.
     */
    data class ChildAlreadyExists(val parentId: Long, val childId: Long) : MessageAddChildError
}
