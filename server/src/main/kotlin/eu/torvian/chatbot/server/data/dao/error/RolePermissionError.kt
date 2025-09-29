package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during role-permission assignment operations.
 */
sealed interface RolePermissionError {
    /** Foreign key constraint violation (role or permission doesn't exist). */
    data class ForeignKeyViolation(val reason: String) : RolePermissionError

    /** Assignment between role and permission was not found. */
    data class AssignmentNotFound(val roleId: Long, val permissionId: Long) : RolePermissionError

    /** Assignment between role and permission already exists. */
    data class AssignmentAlreadyExists(val roleId: Long, val permissionId: Long) : RolePermissionError
}

