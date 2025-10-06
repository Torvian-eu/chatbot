package eu.torvian.chatbot.server.data.entities.mappers

import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.server.data.entities.UserGroupEntity

/**
 * Converts a [UserGroupEntity] (server-side data entity) to a [UserGroup] (shared model).
 *
 * @return A [UserGroup] instance with the same data
 */
fun UserGroupEntity.toUserGroup(): UserGroup {
    return UserGroup(
        id = this.id,
        name = this.name,
        description = this.description
    )
}

