package eu.torvian.chatbot.common.models.tool

/**
 * Validates full tool names against the character set that is safe to send to LLM providers.
 *
 * Some LLM providers reject characters such as dots, spaces, or slashes in tool names. A tool name is the public
 * identifier the LLM sees; for MCP tools it is derived from an untrusted, server-defined raw name (which is
 * preserved separately for dispatch). This validator enforces the allowed character set `^[a-zA-Z0-9_-]+$` and a
 * length bound, returning a human-readable error so the caller can reject an invalid name.
 *
 * @property config The validation rules to apply.
 */
class ToolNameValidator(
    private val config: ToolNameSanitizerConfig = ToolNameSanitizerConfig()
) {
    /**
     * Validates a full tool name.
     *
     * Unlike a worker prefix, a tool name is never blank and may be up to the database column length. Only the
     * character set and length bounds are checked.
     *
     * @param name The tool name to validate.
     * @return `null` if valid, or a human-readable error message if invalid.
     */
    fun validate(name: String): String? {
        return when {
            name.isBlank() ->
                "Tool name must not be blank"
            name.length < config.minLength ->
                "Tool name must be at least ${config.minLength} characters"
            name.length > config.maxLength ->
                "Tool name must be no more than ${config.maxLength} characters"
            !config.allowedRegex.matches(name) ->
                "Tool name can only contain letters, numbers, hyphens, and underscores"
            else -> null
        }
    }
}

