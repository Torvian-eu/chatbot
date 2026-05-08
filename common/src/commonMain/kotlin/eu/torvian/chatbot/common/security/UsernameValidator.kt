package eu.torvian.chatbot.common.security

import kotlinx.serialization.Serializable

/**
 * Configuration for username validation rules.
 *
 * @param minLength Minimum required username length
 * @param maxLength Maximum allowed username length
 * @param allowedRegexPattern Regex pattern string that defines valid characters in a username
 */
@Serializable
data class UsernameValidationConfig(
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val allowedRegexPattern: String = DEFAULT_REGEX_PATTERN
) {
    /**
     * The compiled [Regex] derived from [allowedRegexPattern].
     */
    val allowedRegex: Regex by lazy { Regex(allowedRegexPattern) }

    companion object {
        const val DEFAULT_MIN_LENGTH = 3
        const val DEFAULT_MAX_LENGTH = 50
        const val DEFAULT_REGEX_PATTERN = "^[a-zA-Z0-9_-]+$"
    }
}

/**
 * Validates usernames against configurable rules.
 *
 * This validator enforces length constraints and character restrictions
 * according to the provided [UsernameValidationConfig].
 *
 * @param config The validation rules to apply
 */
class UsernameValidator(
    private val config: UsernameValidationConfig = UsernameValidationConfig()
) {

    /**
     * Validates a username against the configured rules.
     *
     * @param username The username to validate
     * @return `null` if valid, or an error message string if invalid
     */
    fun validate(username: String): String? {
        return when {
            username.isBlank() -> "Username is required"
            username.length < config.minLength ->
                "Username must be at least ${config.minLength} characters"
            username.length > config.maxLength ->
                "Username must be no more than ${config.maxLength} characters"
            !config.allowedRegex.matches(username) ->
                "Username can only contain letters, numbers, hyphens, and underscores"
            else -> null
        }
    }
}
