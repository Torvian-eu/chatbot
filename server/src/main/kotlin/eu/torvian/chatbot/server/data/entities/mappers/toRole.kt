package eu.torvian.chatbot.server.data.entities.mappers

import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.server.data.entities.RoleEntity

/**
 * Extension function to map a [RoleEntity] to a [Role] for API responses.
 */
fun RoleEntity.toRole(): Role {
    return Role(
        id = this.id,
        name = this.name,
        description = this.description
    )
}
