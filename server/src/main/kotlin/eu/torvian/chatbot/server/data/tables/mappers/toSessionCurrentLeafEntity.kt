package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.SessionCurrentLeafEntity
import eu.torvian.chatbot.server.data.tables.SessionCurrentLeafTable
import org.jetbrains.exposed.sql.ResultRow

/**
 * Extension function to map an Exposed [ResultRow] to a [SessionCurrentLeafEntity].
 */
fun ResultRow.toSessionCurrentLeafEntity(): SessionCurrentLeafEntity {
    return SessionCurrentLeafEntity(
        sessionId = this[SessionCurrentLeafTable.sessionId].value,
        messageId = this[SessionCurrentLeafTable.messageId].value
    )
}