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
     * Indicates that the child message with the specified ID was not found.
     */
    data class ChildNotFound(val childId: Long) : MessageAddChildError

    /**
     * Indicates that the child message already exists in the parent's children list.
     */
    data class ChildAlreadyExists(val parentId: Long, val childId: Long) : MessageAddChildError

    /**
     * Indicates that the child message already has a parent.
     */
    data class ChildAlreadyHasParent(val childId: Long, val currentParentId: Long) : MessageAddChildError

    /**
     * Indicates that the child message is the parent message itself.
     */
    data class ChildIsParent(val parentId: Long, val childId: Long) : MessageAddChildError

    /**
     * Indicates that the child and parent messages are not in the same session.
     */
    data class ChildNotInSession(val childId: Long, val parentId: Long, val sessionId: Long) : MessageAddChildError
}
