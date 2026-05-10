package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.DeviceVerificationTokenEntity
import eu.torvian.chatbot.server.data.tables.DeviceVerificationTokensTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed result row from [DeviceVerificationTokensTable] into a device verification token entity.
 */
fun ResultRow.toDeviceVerificationTokenEntity(): DeviceVerificationTokenEntity {
    return DeviceVerificationTokenEntity(
        id = this[DeviceVerificationTokensTable.id].value,
        userId = this[DeviceVerificationTokensTable.userId].value,
        deviceId = this[DeviceVerificationTokensTable.deviceId],
        token = this[DeviceVerificationTokensTable.token],
        expiresAt = Instant.fromEpochMilliseconds(this[DeviceVerificationTokensTable.expiresAt]),
        createdAt = Instant.fromEpochMilliseconds(this[DeviceVerificationTokensTable.createdAt])
    )
}
