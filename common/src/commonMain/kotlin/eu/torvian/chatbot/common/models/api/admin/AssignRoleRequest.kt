package eu.torvian.chatbot.common.models.api.admin

import kotlinx.serialization.Serializable

/**
 * Request body for assigning a role to a user (admin only).
 *
 * @property roleId The ID of the role to assign
 */
@Serializable
data class AssignRoleRequest(
    val roleId: Long
)
