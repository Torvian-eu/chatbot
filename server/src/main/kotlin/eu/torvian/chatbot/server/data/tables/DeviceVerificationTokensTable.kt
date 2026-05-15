package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table for device verification tokens used in email-based device trust.
 *
 * This table stores temporary tokens that allow users to verify their identity
 * via email and promote an unrecognized device to "Trusted". Tokens are single-use
 * and expire after a configurable duration (typically 1 hour).
 */
object DeviceVerificationTokensTable : LongIdTable("device_verification_tokens") {
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 36) // UUID format
    val token = varchar("token", 36).uniqueIndex() // UUID format
    val expiresAt = long("expires_at") // Epoch milliseconds
    val createdAt = long("created_at") // Epoch milliseconds

    init {
        // Composite index for rate limiting queries
        index(false, userId, deviceId)
    }
}
