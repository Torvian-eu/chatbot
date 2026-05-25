package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.UserPreferenceEntity
import eu.torvian.chatbot.server.data.tables.UserPreferencesTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps a database result row into a [UserPreferenceEntity].
 */
fun ResultRow.toUserPreferenceEntity(): UserPreferenceEntity {
    return UserPreferenceEntity(
        id = this[UserPreferencesTable.id].value,
        userId = this[UserPreferencesTable.userId].value,
        deviceId = this.getOrNull(UserPreferencesTable.deviceId)?.value,
        scopeId = this[UserPreferencesTable.scopeId],
        prefKey = this[UserPreferencesTable.prefKey],
        prefValue = this[UserPreferencesTable.prefValue],
        updatedAt = Instant.fromEpochMilliseconds(this[UserPreferencesTable.updatedAt])
    )
}
