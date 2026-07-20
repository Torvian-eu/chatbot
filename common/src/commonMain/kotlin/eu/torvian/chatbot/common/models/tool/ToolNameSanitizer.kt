package eu.torvian.chatbot.common.models.tool

/**
 * Normalizes an arbitrary tool name into a form that is safe to send to LLM providers.
 *
 * Some LLM providers only accept the characters `a-z`, `A-Z`, `0-9`, underscore (`_`), and dash (`-`) in tool
 * (function) names. MCP servers, however, may expose tools whose names contain other characters (dots, spaces,
 * slashes, Unicode, control characters, etc.) that are perfectly valid for the MCP protocol but rejected by the
 * LLM. This sanitizer converts every unsupported character into a single legal replacement character so the
 * resulting name is LLM-safe, while the original raw name is preserved elsewhere for talking to the MCP server.
 *
 * The transformation is purely character-based and therefore deterministic: the same input always yields the same
 * output, which keeps persisted public names stable across repeated discovery/refresh cycles.
 *
 * @property config The validation/sanitization rules (character set and replacement policy) to apply.
 */
class ToolNameSanitizer(
    private val config: ToolNameSanitizerConfig = ToolNameSanitizerConfig()
) {
    /**
     * Sanitizes a tool name so that it only contains LLM-safe characters.
     *
     * Every character that is not part of [ToolNameSanitizerConfig.allowedRegex] is replaced with
     * [ToolNameSanitizerConfig.replacement]. Consecutive illegal characters are collapsed into a single
     * replacement so `get /weather` becomes `get_weather` rather than `get__weather`. The result is trimmed to
     * [ToolNameSanitizerConfig.maxLength]; if truncation leaves a trailing replacement character it is dropped so
     * the final name never ends with the replacement separator.
     *
     * @param name The raw tool name (e.g. an MCP tool name) to sanitize.
     * @return An LLM-safe name containing only characters from the allowed set. Never `null` and never blank
     *   for non-blank input; a blank input is returned unchanged.
     */
    fun sanitize(name: String): String {
        if (name.isBlank()) return name
        val replaced = name.map { char ->
            if (config.allowedRegex.matches(char.toString())) char else config.replacement
        }.joinToString("")
        // Collapse repeated replacement characters into a single one (e.g. "a  b" -> "a_b").
        val collapsed = replaced.replace("${config.replacement}{2,}".toRegex(), config.replacement.toString())
        val truncated = if (collapsed.length > config.maxLength) collapsed.take(config.maxLength) else collapsed
        // Drop leading/trailing replacement separators so the name never starts or ends with one.
        return truncated.trim(config.replacement)
    }
}
