package eu.torvian.chatbot.common.models.tool

import kotlinx.serialization.Serializable

/**
 * Configuration for [ToolNameSanitizer] and [ToolNameValidator].
 *
 * The allowed character set mirrors [ToolNamePrefixValidationConfig.DEFAULT_REGEX_PATTERN] (`^[a-zA-Z0-9_-]+$`),
 * the characters that are safe to embed in tool names sent to LLM providers. Unlike a worker prefix, a full tool
 * name is never blank and may be longer, so the defaults use `minLength = 1` and `maxLength = 255` (matching the
 * database column).
 *
 * @param allowedRegexPattern Regex pattern string that defines valid characters in a tool name.
 * @param replacement Character used by [ToolNameSanitizer] to replace any unsupported character.
 * @param minLength Minimum required tool-name length (only enforced by [ToolNameValidator]).
 * @param maxLength Maximum allowed tool-name length; also the cap applied by [ToolNameSanitizer].
 */
@Serializable
data class ToolNameSanitizerConfig(
    val allowedRegexPattern: String = DEFAULT_REGEX_PATTERN,
    val replacement: Char = DEFAULT_REPLACEMENT,
    val minLength: Int = DEFAULT_MIN_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH
) {
    /**
     * The compiled [Regex] derived from [allowedRegexPattern], used to test individual characters.
     */
    val allowedRegex: Regex by lazy { Regex(allowedRegexPattern) }

    companion object {
        const val DEFAULT_REGEX_PATTERN = "^[a-zA-Z0-9_-]+$"
        const val DEFAULT_REPLACEMENT: Char = '_'
        const val DEFAULT_MIN_LENGTH = 1
        const val DEFAULT_MAX_LENGTH = 255
    }
}
