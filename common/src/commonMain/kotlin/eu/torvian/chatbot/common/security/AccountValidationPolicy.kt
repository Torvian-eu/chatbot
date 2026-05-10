package eu.torvian.chatbot.common.security

import kotlinx.serialization.Serializable

/**
 * Aggregates all account validation configuration into a single policy object.
 *
 * This is the source of truth for both username and password validation rules
 * used across the application.
 *
 * @param passwordConfig Configuration for password validation
 * @param usernameConfig Configuration for username validation
 * @param maxFailedAttempts Maximum number of failed login attempts allowed within the lockout window (default: 10)
 * @param lockoutWindowMinutes Duration in minutes for the sliding lockout window (default: 5)
 */
@Serializable
data class AccountValidationPolicy(
    val passwordConfig: PasswordValidationConfig = PasswordValidationConfig(),
    val usernameConfig: UsernameValidationConfig = UsernameValidationConfig(),
    val maxFailedAttempts: Int = 10,
    val lockoutWindowMinutes: Int = 5
)
