package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserTrustedIpEntity
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed result row from [UserTrustedIpsTable] into a trusted-IP entity.
 */
fun ResultRow.toUserTrustedIpEntity(): UserTrustedIpEntity {
    return UserTrustedIpEntity(
        id = this[UserTrustedIpsTable.id].value,
        userId = this[UserTrustedIpsTable.userId].value,
        ipAddress = this[UserTrustedIpsTable.ipAddress],
        isTrusted = this[UserTrustedIpsTable.isTrusted],
        isAcknowledged = this[UserTrustedIpsTable.isAcknowledged],
        firstUsedAt = Instant.fromEpochMilliseconds(this[UserTrustedIpsTable.firstUsedAt]),
        lastUsedAt = Instant.fromEpochMilliseconds(this[UserTrustedIpsTable.lastUsedAt])
    )
}

