package eu.torvian.chatbot.server.data.models

import eu.torvian.chatbot.common.models.ChatGroup
import org.jetbrains.exposed.dao.id.LongIdTable

/**
 * Exposed table definition for chat session groups.
 * Corresponds to the [ChatGroup] entity.
 *
 * @property name The unique name of the chat group
 * @property createdAt The timestamp when the group was created
 */
object ChatGroups : LongIdTable("chat_groups") {
    val name = varchar("name", 255).uniqueIndex()
    val createdAt = long("created_at").default(System.currentTimeMillis())
}
