package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.RoleEntity
import eu.torvian.chatbot.server.data.tables.RolesTable
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [RoleEntity].
 */
fun ResultRow.toRoleEntity(): RoleEntity {
    return RoleEntity(
        id = this[RolesTable.id].value,
        name = this[RolesTable.name],
        description = this[RolesTable.description]
    )
}

