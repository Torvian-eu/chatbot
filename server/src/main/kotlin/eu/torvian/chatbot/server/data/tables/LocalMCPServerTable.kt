package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption

/**
 * Minimal server-side storage for Local MCP Server identification and ownership.
 *
 * This table stores only the essential information needed for server-side operations:
 * - Unique ID generation
 * - User ownership tracking
 * - Tool linkage support
 *
 * Full MCP server configurations (command, arguments, environment variables, etc.) are
 * stored client-side using SQLDelight, as they are platform-specific and may contain
 * sensitive data that should remain on the client device.
 *
 * @property userId Reference to the user who owns this MCP server configuration. Cascade
 * deletion ensures cleanup when the user is deleted.
 */
object LocalMCPServerTable : LongIdTable("local_mcp_servers") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
}

