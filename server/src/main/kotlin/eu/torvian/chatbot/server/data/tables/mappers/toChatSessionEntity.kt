package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [org.jetbrains.exposed.sql.ResultRow] to a [eu.torvian.chatbot.server.data.entities.ChatSessionEntity].
 */
fun ResultRow.toChatSessionEntity(): ChatSessionEntity {
    return ChatSessionEntity(
        id = this[ChatSessionTable.id].value,
        name = this[ChatSessionTable.name],
        createdAt = Instant.Companion.fromEpochMilliseconds(this[ChatSessionTable.createdAt]),
        updatedAt = Instant.Companion.fromEpochMilliseconds(this[ChatSessionTable.updatedAt]),
        groupId = this[ChatSessionTable.groupId]?.value,
        currentModelId = this[ChatSessionTable.currentModelId]?.value,
        currentSettingsId = this[ChatSessionTable.currentSettingsId]?.value
    )
}