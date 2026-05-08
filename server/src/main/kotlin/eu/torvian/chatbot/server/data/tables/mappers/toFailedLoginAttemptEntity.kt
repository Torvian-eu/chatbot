package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.FailedLoginAttemptEntity
import eu.torvian.chatbot.server.data.tables.FailedLoginAttemptsTable
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Maps an Exposed [ResultRow] from [FailedLoginAttemptsTable] to a [FailedLoginAttemptEntity].
 */
fun ResultRow.toFailedLoginAttemptEntity(): FailedLoginAttemptEntity {
    return FailedLoginAttemptEntity(
        id = this[FailedLoginAttemptsTable.id].value,
        username = this[FailedLoginAttemptsTable.username],
        ipAddress = this[FailedLoginAttemptsTable.ipAddress],
        deviceId = this[FailedLoginAttemptsTable.deviceId],
        attemptTimestamp = this[FailedLoginAttemptsTable.attemptTimestamp]
    )
}

