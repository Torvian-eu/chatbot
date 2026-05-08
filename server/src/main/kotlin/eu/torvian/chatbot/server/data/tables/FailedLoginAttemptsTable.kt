package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for tracking failed login attempts to enforce sliding-window lockout policies.
 *
 * Each record represents a single failed login attempt, storing the targeted username,
 * originating IP address, client device ID, and timestamp. This data allows the server
 * to block further attempts when either the username or IP exceeds the configured
 * maximum failures within the configured time window.
 *
 * @property username Non-nullable username of the account targeted by the failed attempt.
 * @property ipAddress Non-nullable IP address from which the failed attempt originated.
 * @property deviceId Non-nullable client-side device identifier associated with the attempt.
 * @property attemptTimestamp Timestamp (epoch milliseconds) when the failed attempt occurred.
 */
object FailedLoginAttemptsTable : LongIdTable("failed_login_attempts") {
    val username = varchar("username", 255)
    val ipAddress = varchar("ip_address", 45)
    val deviceId = varchar("device_id", 36)
    val attemptTimestamp = long("attempt_timestamp")

    init {
        // Index for efficient username-based lookups within the sliding window
        index(false, username)
        // Index for efficient IP-based lookups within the sliding window
        index(false, ipAddress)
        // Index for timestamp-based range queries (sliding window)
        index(false, attemptTimestamp)
    }
}
