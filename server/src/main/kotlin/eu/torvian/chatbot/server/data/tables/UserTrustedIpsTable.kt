package eu.torvian.chatbot.server.data.tables

import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable.firstUsedAt
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable.ipAddress
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable.isAcknowledged
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable.isTrusted
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable.lastUsedAt
import eu.torvian.chatbot.server.data.tables.UserTrustedIpsTable.userId
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for IP trust records used by the authentication service.
 *
 * Each row tracks one user/IP pair and whether the user has acknowledged the event.
 * The combination of user and IP is kept unique so logins can update the existing row
 * instead of creating duplicate trust state.
 */
object UserTrustedIpsTable : LongIdTable("user_trusted_ips") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val ipAddress = varchar("ip_address", 45)
    val isTrusted = bool("is_trusted").default(false)
    val isAcknowledged = bool("is_acknowledged").default(true)
    val firstUsedAt = long("first_used_at")
    val lastUsedAt = long("last_used_at")

    init {
        uniqueIndex("user_trusted_ips_user_id_ip_address_unique", userId, ipAddress)
    }
}

