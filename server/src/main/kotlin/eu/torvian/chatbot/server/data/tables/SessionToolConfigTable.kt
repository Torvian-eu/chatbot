package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.SessionToolConfigTable.isEnabled
import eu.torvian.chatbot.server.data.tables.SessionToolConfigTable.sessionId
import eu.torvian.chatbot.server.data.tables.SessionToolConfigTable.toolDefinitionId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for session-specific tool configuration.
 *
 * This junction table allows users to enable/disable specific tools for individual chat sessions.
 * A tool must be globally enabled (in ToolDefinitionTable) AND enabled for the session
 * (in this table) to be available during conversation.
 *
 * If no row exists for a session-tool pair, the tool is considered disabled for that session
 * by default. This allows for explicit opt-in behavior.
 *
 * @property sessionId Reference to the chat session
 * @property toolDefinitionId Reference to the tool definition
 * @property isEnabled Whether this tool is enabled for this specific session
 */
object SessionToolConfigTable : Table("session_tool_config") {
    val sessionId = reference("session_id", ChatSessionTable, onDelete = ReferenceOption.CASCADE)
    val toolDefinitionId = reference("tool_definition_id", ToolDefinitionTable, onDelete = ReferenceOption.CASCADE)
    val isEnabled = bool("is_enabled").default(true)

    override val primaryKey = PrimaryKey(sessionId, toolDefinitionId)
}
