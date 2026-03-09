package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import eu.torvian.chatbot.server.data.tables.ChatSessionTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Extension function to map an Exposed [ResultRow] to a [ChatSessionEntity].
 */
fun ResultRow.toChatSessionEntity(): ChatSessionEntity {
    return ChatSessionEntity(
        id = this[ChatSessionTable.id].value,
        name = this[ChatSessionTable.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[ChatSessionTable.updatedAt]),
        groupId = this[ChatSessionTable.groupId]?.value,
        currentModelId = this[ChatSessionTable.currentModelId]?.value,
        currentSettingsId = this[ChatSessionTable.currentSettingsId]?.value
    )
}