package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.entities.SecurityAuditEntity
import eu.torvian.chatbot.server.data.tables.SecurityAuditTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed result row from [SecurityAuditTable] into a security audit entity.
 */
fun ResultRow.toSecurityAuditEntity(): SecurityAuditEntity {
    return SecurityAuditEntity(
        id = this[SecurityAuditTable.id].value,
        userId = this[SecurityAuditTable.userId].value,
        deviceId = this[SecurityAuditTable.deviceId],
        ipAddress = this[SecurityAuditTable.ipAddress],
        createdAt = Instant.fromEpochMilliseconds(this[SecurityAuditTable.createdAt]),
        status = SecurityAuditStatus.valueOf(this[SecurityAuditTable.status]),
        resolvedAt = this[SecurityAuditTable.resolvedAt]?.let { Instant.fromEpochMilliseconds(it) }
    )
}
