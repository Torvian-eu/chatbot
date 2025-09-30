package eu.torvian.chatbot.server.data.entities

import kotlinx.datetime.Instant

/**
 * Represents a row from the 'user_role_assignments' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 * Links users to their assigned roles in a many-to-many relationship.
 *
 * @property userId The ID of the user who has been assigned this role.
 * @property roleId The ID of the role assigned to the user.
 * @property assignedAt The timestamp when this role was assigned to the user.
 */
data class UserRoleAssignmentEntity(
    val userId: Long,
    val roleId: Long,
    val assignedAt: Instant
)
