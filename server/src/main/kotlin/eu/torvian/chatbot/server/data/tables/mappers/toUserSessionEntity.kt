package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.tables.UserSessionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Extension function to map an Exposed [ResultRow] to a [UserSessionEntity].
 */
fun ResultRow.toUserSessionEntity(): UserSessionEntity {
    return UserSessionEntity(
        id = this[UserSessionsTable.id].value,
        userId = this[UserSessionsTable.userId].value,
        expiresAt = Instant.fromEpochMilliseconds(this[UserSessionsTable.expiresAt]),
        createdAt = Instant.fromEpochMilliseconds(this[UserSessionsTable.createdAt]),
        lastAccessed = Instant.fromEpochMilliseconds(this[UserSessionsTable.lastAccessed]),
        ipAddress = this[UserSessionsTable.ipAddress]
    )
}
