package eu.torvian.chatbot.server.data.entities

/**
 * Represents a row from the 'failed_login_attempts' database table.
 * This is a direct mapping of the table columns for server-side data handling.
 *
 * @property id Unique identifier for the failed login attempt record.
 * @property username The username that was targeted in the failed attempt (non-nullable).
 * @property ipAddress The IP address from which the failed attempt originated (non-nullable).
 * @property deviceId The client-side device identifier (non-nullable).
 * @property attemptTimestamp Timestamp (epoch millis) when the failed attempt occurred.
 */
data class FailedLoginAttemptEntity(
    val id: Long,
    val username: String,
    val ipAddress: String,
    val deviceId: String,
    val attemptTimestamp: Long
)

