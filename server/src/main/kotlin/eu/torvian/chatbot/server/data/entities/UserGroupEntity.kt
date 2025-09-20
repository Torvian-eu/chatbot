package eu.torvian.chatbot.server.data.entities

/**
 * Represents a row from the 'user_groups' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the user group.
 * @property name Unique name of the group (e.g., "All Users", "Team A", "Developers").
 * @property description Optional description explaining the group's purpose.
 */
data class UserGroupEntity(
    val id: Long,
    val name: String,
    val description: String?
)
