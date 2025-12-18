package eu.torvian.chatbot.server.data.dao.error

/**
 * Error types for user tool approval preference DAO operations.
 */
sealed class UserToolApprovalPreferenceError {
    /**
     * Indicates that a preference for the specified user and tool definition was not found.
     *
     * @property userId The user ID
     * @property toolDefinitionId The tool definition ID
     */
    data class NotFound(val userId: Long, val toolDefinitionId: Long) : UserToolApprovalPreferenceError()
}

/**
 * Error types for setPreference operation.
 */
sealed class SetPreferenceError : UserToolApprovalPreferenceError() {
    /**
     * Indicates a foreign key constraint violation when setting a preference.
     * This is raised when either the referenced user or tool definition does not exist.
     *
     * @property message Descriptive error message
     */
    data class ForeignKeyViolation(val message: String) : SetPreferenceError()
}

