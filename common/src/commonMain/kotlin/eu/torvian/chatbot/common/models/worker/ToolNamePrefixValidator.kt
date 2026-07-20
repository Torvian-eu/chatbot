package eu.torvian.chatbot.common.models.worker

/**
 * Validates worker tool-name prefixes against the character set that is safe to embed in the public
 * names of built-in tools sent to LLM providers.
 *
 * A prefix is concatenated (without a separator) to a canonical built-in tool name to form the public
 * tool name the LLM sees. Because some providers reject characters such as dots, spaces, or slashes in
 * tool names, the prefix is restricted to letters, digits, underscores, and dashes.
 *
 * @property config The validation rules to apply.
 */
class ToolNamePrefixValidator(
    private val config: ToolNamePrefixValidationConfig = ToolNamePrefixValidationConfig()
) {
    /**
     * Validates a worker tool-name prefix.
     *
     * A blank or empty prefix is always valid: it represents "no prefix" and is persisted as `null`.
     * Only non-blank prefixes are checked against the length bounds and the allowed-character regex.
     *
     * @param prefix The prefix to validate (may be blank to indicate no prefix).
     * @return `null` if valid, or a human-readable error message if invalid.
     */
    fun validate(prefix: String): String? {
        if (prefix.isBlank()) return null
        return when {
            prefix.length < config.minLength ->
                "Prefix must be at least ${config.minLength} characters"
            prefix.length > config.maxLength ->
                "Prefix must be no more than ${config.maxLength} characters"
            !config.allowedRegex.matches(prefix) ->
                "Prefix can only contain letters, numbers, hyphens, and underscores"
            else -> null
        }
    }
}

