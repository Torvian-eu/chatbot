package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.PermissionEntity
import eu.torvian.chatbot.server.data.tables.PermissionsTable
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [PermissionEntity].
 */
fun ResultRow.toPermissionEntity(): PermissionEntity {
    return PermissionEntity(
        id = this[PermissionsTable.id].value,
        action = this[PermissionsTable.action],
        subject = this[PermissionsTable.subject]
    )
}

