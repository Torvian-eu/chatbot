package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.server.data.tables.ChatGroupTable
import org.jetbrains.exposed.sql.ResultRow
import kotlin.time.Instant

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