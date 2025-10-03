package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Represents a user-defined group used for sharing and access control.
 * This is a lightweight model shared between client and server.
 *
 * @property id Unique identifier of the group
 * @property name Unique name of the group (e.g., "All Users", "Developers")
 * @property description Optional description of the group's purpose
 */
@Serializable
data class UserGroup(
    val id: Long,
    val name: String,
    val description: String?
)
