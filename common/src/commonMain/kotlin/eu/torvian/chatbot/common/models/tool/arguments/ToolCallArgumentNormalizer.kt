package eu.torvian.chatbot.common.models.tool.arguments

import kotlinx.serialization.json.Json

/**
 * Validates tool-call argument JSON at the boundary between model output and persisted conversation state.
 *
 * Literal JSON control characters are escaped only while inside string literals; no structural repair is
 * attempted. This keeps replay data transport-safe without changing the meaning of valid JSON.
 */
object ToolCallArgumentNormalizer {
    /** Strict parser used after the deliberately narrow string-literal normalization pass. */
    private val strictJson: Json = Json.Default

    /**
     * Result of normalizing one nullable tool-call argument string.
     */
    sealed interface Result {
        /** A null input, or a valid JSON string after control-character escaping. */
        data class Valid(val value: String?) : Result

        /** Input that remained invalid after the narrow normalization pass. */
        data class Invalid(val input: String, val message: String) : Result
    }

    /**
     * Escapes literal U+0000..U+001F characters in strings and strictly validates the result.
     *
     * @param input Raw provider-emitted argument JSON, or null for a parameterless call.
     * @return A replay-safe value or a structured validation failure.
     */
    fun normalize(input: String?): Result {
        if (input == null) return Result.Valid(null)
        val normalized = buildString(input.length) {
            var inString = false
            var escaped = false
            input.forEach { character ->
                when {
                    escaped -> {
                        append(character)
                        escaped = false
                    }
                    character == '\\' && inString -> {
                        append(character)
                        escaped = true
                    }
                    character == '"' -> {
                        append(character)
                        inString = !inString
                    }
                    inString && character.code in 0..0x1f -> append(
                        when (character) {
                            '\b' -> "\\b"
                            '\t' -> "\\t"
                            '\n' -> "\\n"
                            '\u000C' -> "\\f"
                            '\r' -> "\\r"
                            else -> "\\u" + character.code.toString(16).padStart(4, '0')
                        }
                    )
                    else -> append(character)
                }
            }
        }
        return try {
            strictJson.parseToJsonElement(normalized)
            Result.Valid(normalized)
        } catch (exception: IllegalArgumentException) {
            Result.Invalid(input, exception.message ?: "Invalid JSON tool-call arguments")
        }
    }
}
