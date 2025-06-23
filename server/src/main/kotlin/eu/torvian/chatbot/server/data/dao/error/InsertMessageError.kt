package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents errors specific to the insertMessage operation in MessageDao.
 */
sealed interface InsertMessageError {
    /**
     * Indicates that the session ID does not exist.
     */
    data class SessionNotFound(val sessionId: Long) : InsertMessageError

    /**
     * Indicates that the parent message ID does not belong to the specified session.
     */
    data class ParentNotInSession(val parentId: Long, val sessionId: Long) : InsertMessageError
}
