package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new chat session group.
 *
 * @property name The name for the new group.
 */
@Serializable
data class CreateGroupRequest(
    val name: String
)