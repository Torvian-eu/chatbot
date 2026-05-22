package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserDeviceEntity
import eu.torvian.chatbot.server.data.tables.UserDevicesTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps a database result row into a [UserDeviceEntity].
 */
fun ResultRow.toUserDeviceEntity(): UserDeviceEntity {
    return UserDeviceEntity(
        id = this[UserDevicesTable.id].value,
        userId = this[UserDevicesTable.userId].value,
        clientDeviceId = this[UserDevicesTable.clientDeviceId],
        deviceName = this[UserDevicesTable.deviceName],
        createdAt = Instant.fromEpochMilliseconds(this[UserDevicesTable.createdAt]),
        lastUsedAt = Instant.fromEpochMilliseconds(this[UserDevicesTable.lastUsedAt])
    )
}

