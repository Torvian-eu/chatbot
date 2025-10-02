package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during user-role assignment operations.
 */
sealed interface UserRoleAssignmentError {
    /** Foreign key constraint violation (user or role doesn't exist). */
    data class ForeignKeyViolation(val reason: String) : UserRoleAssignmentError

    /** Assignment between user and role was not found. */
    data class AssignmentNotFound(val userId: Long, val roleId: Long) : UserRoleAssignmentError

    /** Assignment between user and role already exists. */
    data class AssignmentAlreadyExists(val userId: Long, val roleId: Long) : UserRoleAssignmentError
}

