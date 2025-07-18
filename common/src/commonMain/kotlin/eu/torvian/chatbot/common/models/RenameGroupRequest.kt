package eu.torvian.chatbot.common.models

import kotlinx.serialization.Serializable

/**
 * Request body for renaming a chat session group.
 * Note: The group ID is part of the URL path for this endpoint.
 *
 * @property name The new name for the group.
 */
@Serializable
data class RenameGroupRequest(
    val name: String
)