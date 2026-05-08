package eu.torvian.chatbot.server.data.dao

/**
 * Data access operations for failed login attempts.
 *
 * This DAO provides the persistence layer for the sliding-window lockout feature,
 * allowing the server to track and count failed login attempts by username and IP address.
 */
interface FailedLoginAttemptDao {
    /**
     * Records a new failed login attempt in the database.
     *
     * @param username The username that was targeted in the failed attempt (non-nullable).
     * @param ipAddress The IP address from which the failed attempt originated (non-nullable).
     * @param deviceId The client-side device identifier (non-nullable).
     */
    suspend fun recordFailure(username: String, ipAddress: String, deviceId: String)

    /**
     * Counts the number of failed login attempts for a specific username within the sliding window.
     *
     * @param username The username to check.
     * @param sinceMillis The start of the sliding window (epoch millis).
     * @return The number of failed attempts for this username since the given timestamp.
     */
    suspend fun countFailuresByUsername(username: String, sinceMillis: Long): Int

    /**
     * Counts the number of failed login attempts for a specific IP address within the sliding window.
     *
     * @param ipAddress The IP address to check.
     * @param sinceMillis The start of the sliding window (epoch millis).
     * @return The number of failed attempts from this IP address since the given timestamp.
     */
    suspend fun countFailuresByIp(ipAddress: String, sinceMillis: Long): Int

    /**
     * Clears all failed login attempts for a given username.
     *
     * This is called after a successful login to reset the username-based lockout counter,
     * preventing a user from being locked out after correcting their credentials.
     * IP-based failures are NOT cleared, preventing reset attacks.
     *
     * @param username The username whose records should be cleared.
     */
    suspend fun clearFailures(username: String)

    /**
     * Removes old failed login attempt records from the database to prevent unbounded growth.
     *
     * This cleanup mechanism ensures the `failed_login_attempts` table stays lean by removing
     * records that have expired outside the sliding window. It is typically called after
     * recording a new failure, using the current lockout window as the threshold.
     *
     * @param thresholdMillis Records with timestamps older than this value (epoch millis) will be deleted.
     */
    suspend fun cleanupOldRecords(thresholdMillis: Long)
}
