package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Request body for assigning a chat session to a group.
 *
 * @property groupId The ID of the group to assign the session to, or null to ungroup the session.
 */
@Serializable
data class AssignSessionToGroupRequest(
    val groupId: Long?
)