package eu.torvian.chatbot.common.models.worker

import kotlinx.serialization.Serializable

/**
 * Configuration for worker tool-name prefix validation rules.
 *
 * The allowed character set ("^[a-zA-Z0-9_-]+$") matches the characters that are safe to embed in the
 * public names of built-in tools sent to LLM providers. The minimum length is `1` because a single
 * character prefix (e.g. `"x_"`) is legitimate, unlike account usernames which require a longer minimum.
 *
 * @param minLength Minimum required prefix length (only enforced for non-blank prefixes).
 * @param maxLength Maximum allowed prefix length; kept well under the 255-char DB column to leave room
 *   for the longest canonical tool name.
 * @param allowedRegexPattern Regex pattern string that defines valid characters in a prefix.
 */
@Serializable
data class ToolNamePrefixValidationConfig(
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val allowedRegexPattern: String = DEFAULT_REGEX_PATTERN
) {
    /**
     * The compiled [Regex] derived from [allowedRegexPattern].
     */
    val allowedRegex: Regex by lazy { Regex(allowedRegexPattern) }

    companion object {
        const val DEFAULT_MIN_LENGTH = 1
        const val DEFAULT_MAX_LENGTH = 64
        const val DEFAULT_REGEX_PATTERN = "^[a-zA-Z0-9_-]+$"
    }
}

