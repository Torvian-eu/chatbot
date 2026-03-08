package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable.isEnabled
import eu.torvian.chatbot.server.data.tables.LocalMCPServerTable.userId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Minimal server-side storage for Local MCP Server identification and ownership.
 *
 * This table stores only the essential information needed for server-side operations:
 * - Unique ID generation
 * - User ownership tracking
 * - Global enable/disable flag (synced from client)
 * - Tool linkage support
 *
 * Full MCP server configurations (command, arguments, environment variables, etc.) are
 * stored client-side using SQLDelight, as they are platform-specific and may contain
 * sensitive data that should remain on the client device.
 *
 * @property userId Reference to the user who owns this MCP server configuration. Cascade
 * deletion ensures cleanup when the user is deleted.
 * @property isEnabled Global enable/disable flag. If false, ALL tools from this server
 * are unavailable. Kept in sync with the client-side isEnabled flag.
 */
object LocalMCPServerTable : LongIdTable("local_mcp_servers") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val isEnabled = bool("is_enabled").default(true)
}

