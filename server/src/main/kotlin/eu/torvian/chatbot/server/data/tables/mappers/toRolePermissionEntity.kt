package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.RolePermissionEntity
import eu.torvian.chatbot.server.data.tables.RolePermissionsTable
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [RolePermissionEntity].
 */
fun ResultRow.toRolePermissionEntity(): RolePermissionEntity {
    return RolePermissionEntity(
        roleId = this[RolePermissionsTable.roleId].value,
        permissionId = this[RolePermissionsTable.permissionId].value
    )
}

