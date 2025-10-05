package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.common.models.core.ChatGroup
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for chat session groups.
 * Corresponds to the [ChatGroup] DTO.
 *
 * @property name The name of the chat group
 * @property createdAt The timestamp when the group was created
 */
object ChatGroupTable : LongIdTable("chat_groups") {
    val name = varchar("name", 255)
    val createdAt = long("created_at").default(System.currentTimeMillis())
}
