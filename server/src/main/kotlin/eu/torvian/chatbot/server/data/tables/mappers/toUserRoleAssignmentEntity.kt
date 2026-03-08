package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserRoleAssignmentEntity
import eu.torvian.chatbot.server.data.tables.UserRoleAssignmentsTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Extension function to map an Exposed [ResultRow] to a [UserRoleAssignmentEntity].
 */
fun ResultRow.toUserRoleAssignmentEntity(): UserRoleAssignmentEntity {
    return UserRoleAssignmentEntity(
        userId = this[UserRoleAssignmentsTable.userId].value,
        roleId = this[UserRoleAssignmentsTable.roleId].value,
        assignedAt = Instant.fromEpochMilliseconds(this[UserRoleAssignmentsTable.assignedAt])
    )
}
