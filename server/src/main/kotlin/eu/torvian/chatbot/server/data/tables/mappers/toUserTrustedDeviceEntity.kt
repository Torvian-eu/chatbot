package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserTrustedDeviceEntity
import eu.torvian.chatbot.server.data.tables.UserTrustedDevicesTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed result row from [UserTrustedDevicesTable] into a trusted-device entity.
 */
fun ResultRow.toUserTrustedDeviceEntity(): UserTrustedDeviceEntity {
    return UserTrustedDeviceEntity(
        id = this[UserTrustedDevicesTable.id].value,
        userId = this[UserTrustedDevicesTable.userId].value,
        deviceId = this[UserTrustedDevicesTable.deviceId],
        lastIpAddress = this[UserTrustedDevicesTable.lastIpAddress],
        firstSeenAt = Instant.fromEpochMilliseconds(this[UserTrustedDevicesTable.firstSeenAt]),
        lastUsedAt = Instant.fromEpochMilliseconds(this[UserTrustedDevicesTable.lastUsedAt])
    )
}
