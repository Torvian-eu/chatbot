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
 */
@Serializable
data class AccountValidationPolicy(
    val passwordConfig: PasswordValidationConfig = PasswordValidationConfig(),
    val usernameConfig: UsernameValidationConfig = UsernameValidationConfig()
)
