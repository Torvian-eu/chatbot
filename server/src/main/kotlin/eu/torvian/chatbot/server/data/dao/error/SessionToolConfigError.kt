package eu.torvian.chatbot.server.data.dao.error

/**
 * Error types for setting tool enabled status operations.
 */
sealed class SetToolEnabledError {
    /**
     * Indicates that a foreign key constraint was violated during tool configuration.
     * This typically occurs when referencing a non-existent session ID or tool definition ID.
     *
     * @property message A description of which constraint was violated
     */
    data class ForeignKeyViolation(val message: String) : SetToolEnabledError()
}

/**
 * Error types for batch setting tool enabled status operations.
 */
sealed class SetToolsEnabledError {
    /**
     * Indicates that a foreign key constraint was violated during tool configuration.
     * This typically occurs when referencing a non-existent session ID or tool definition ID.
     *
     * @property message A description of which constraint was violated
     */
    data class ForeignKeyViolation(val message: String) : SetToolsEnabledError()
}

/**
 * Error types for clearing tool configuration operations.
 */
sealed class ClearToolConfigError {
    /**
     * Indicates that the referenced session does not exist.
     *
     * @property sessionId The session ID that was not found
     */
    data class SessionNotFound(val sessionId: Long) : ClearToolConfigError()
}

