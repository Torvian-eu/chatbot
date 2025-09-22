package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import eu.torvian.chatbot.server.data.tables.UserGroupsTable
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [UserGroupEntity].
 */
fun ResultRow.toUserGroupEntity(): UserGroupEntity {
    return UserGroupEntity(
        id = this[UserGroupsTable.id].value,
        name = this[UserGroupsTable.name],
        description = this[UserGroupsTable.description]
    )
}
