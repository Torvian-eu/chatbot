package eu.torvian.chatbot.common.models.user

import kotlinx.serialization.Serializable

/**
 * Represents a permission in the system.
 *
 * Permissions follow an action-subject pattern for fine-grained access control.
 * For example: "manage" + "users", "create" + "public_provider".
 *
 * @property id Unique identifier for the permission
 * @property action The action being permitted (e.g., "manage", "create", "delete")
 * @property subject The subject/resource the action applies to (e.g., "users", "public_provider")
 */
@Serializable
data class Permission(
    val id: Long,
    val action: String,
    val subject: String
)
