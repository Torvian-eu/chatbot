package eu.torvian.chatbot.server.service.core.error.session
/**
 * Represents possible errors when assigning or unassigning a session to/from a group.
 */
sealed interface UpdateSessionGroupIdError {
    /**
     * Indicates that the session with the specified ID was not found.
     * Maps from SessionError.SessionNotFound in the DAO layer.
     */
    data class SessionNotFound(val id: Long) : UpdateSessionGroupIdError
    /**
     * Indicates that the target group with the specified ID was not found when assigning.
     * Maps from SessionError.ForeignKeyViolation in the DAO layer.
     */
    data class InvalidRelatedEntity(val message: String) : UpdateSessionGroupIdError
}
