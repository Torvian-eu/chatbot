package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.entities.ChatSessionEntity
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for chat sessions.
 * Corresponds to the [ChatSessionEntity].
 *
 * @property name The name of the chat session
 * @property createdAt Timestamp when the session was created
 * @property updatedAt Timestamp when the session was last updated
 * @property groupId Reference to the chat group this session belongs to
 * @property agentRoleId Optional reference to the user-defined agent role selected for this session.
 *            Model/settings/tools are resolved from the role at turn time; `SET NULL` when the role is
 *            deleted so sessions become inert (non-sendable) until a role is re-selected.
 */
object ChatSessionTable : LongIdTable("chat_sessions") {
    val name = varchar("name", 255)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val groupId = reference("group_id", ChatGroupTable, onDelete = ReferenceOption.SET_NULL).nullable()
    val agentRoleId = reference("agent_role_id", AgentRoleTable, onDelete = ReferenceOption.SET_NULL).nullable()

    // Add index for groupId to speed up grouped session queries (E6.S2)
    init {
        index(false, groupId)
        index(false, agentRoleId)
    }
}
