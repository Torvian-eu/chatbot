package eu.torvian.chatbot.server.data.entities.mappers

import eu.torvian.chatbot.common.models.Permission
import eu.torvian.chatbot.server.data.entities.PermissionEntity

/**
 * Extension function to map a [PermissionEntity] to a [Permission] for API responses.
 */
fun PermissionEntity.toPermission(): Permission {
    return Permission(
        id = this.id,
        action = this.action,
        subject = this.subject
    )
}
