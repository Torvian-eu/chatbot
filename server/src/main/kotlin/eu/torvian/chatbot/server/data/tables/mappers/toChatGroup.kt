package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.tables.ChatGroupTable
import kotlinx.datetime.Instant
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [ChatGroup].
 */
fun ResultRow.toChatGroup(): ChatGroup {
    return ChatGroup(
        id = this[ChatGroupTable.id].value,
        name = this[ChatGroupTable.name],
        createdAt = Instant.fromEpochMilliseconds(this[ChatGroupTable.createdAt])
    )
}