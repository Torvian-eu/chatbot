package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Adds a validation error for every parameter that is not part of the tool's accepted input shape.
 *
 * Mirrors the strict-schema behavior of the worker built-in tools: unknown parameters are rejected
 * so a typo'd or hallucinated argument is surfaced to the LLM instead of being silently ignored.
 *
 * @param input The raw tool input object.
 * @param validKeys The set of accepted parameter names for this tool.
 * @param validationErrors The accumulated validation error list.
 */
internal fun addUnknownParameterErrors(
    input: JsonObject,
    validKeys: Set<String>,
    validationErrors: MutableList<String>,
) {
    for (key in input.keys) {
        if (key !in validKeys) {
            validationErrors.add("Unknown parameter: '$key'")
        }
    }
}

/**
 * Parses a required string parameter, recording a validation error for missing or malformed values.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed string value, or null when the value is missing or invalid.
 */
internal fun parseRequiredString(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): String? {
    val element = input[key]
    if (element == null) {
        validationErrors.add("Missing required argument: $key")
        return null
    }
    if (element !is JsonPrimitive || !element.isString) {
        validationErrors.add("Argument '$key' must be a string")
        return null
    }
    return element.content
}

/**
 * Parses a required integer parameter, recording a validation error for missing or malformed values.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed long value, or null when the value is missing or invalid.
 */
internal fun parseRequiredLong(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): Long? {
    val element = input[key]
    if (element == null) {
        validationErrors.add("Missing required argument: $key")
        return null
    }
    if (element !is JsonPrimitive || element.longOrNull == null) {
        validationErrors.add("Argument '$key' must be an integer")
        return null
    }
    return element.longOrNull
}

/**
 * Parses an optional string parameter.
 *
 * Absent and explicitly-`null` values both decode to `null`; callers merge with the persisted value
 * for the `update_agent_role` patch semantics. Present-but-invalid values produce a validation error.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed string value, null when absent/null, or null with a recorded error when invalid.
 */
internal fun parseOptionalString(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): String? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    if (element !is JsonPrimitive || !element.isString) {
        validationErrors.add("Argument '$key' must be a string")
        return null
    }
    return element.content
}

/**
 * Parses an optional integer parameter.
 *
 * Absent and explicitly-`null` values both decode to `null`; callers merge with the persisted value
 * for the `update_agent_role` patch semantics. Present-but-invalid values produce a validation error.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed long value, null when absent/null, or null with a recorded error when invalid.
 */
internal fun parseOptionalLong(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): Long? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    if (element !is JsonPrimitive || element.longOrNull == null) {
        validationErrors.add("Argument '$key' must be an integer")
        return null
    }
    return element.longOrNull
}

/**
 * Parses an optional boolean parameter.
 *
 * Absent and explicitly-`null` values both decode to `null`, so a caller can preserve the persisted
 * value. Only the JSON literals `true` and `false` are accepted: strings (even `"true"`) and numbers
 * are rejected, so a non-null result always means the caller sent an explicit boolean.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed boolean, null when absent/null, or null with a recorded error when invalid.
 */
internal fun parseOptionalBoolean(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): Boolean? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    val value = (element as? JsonPrimitive)?.booleanOrNull
    if (value == null) {
        validationErrors.add("Argument '$key' must be a boolean")
        return null
    }
    return value
}

/**
 * Parses an optional array-of-integers parameter, discarding order and duplicates.
 *
 * Absent and explicitly-`null` values both decode to `null`. Non-integer elements produce one
 * validation error per offending index so the LLM sees every issue at once.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed set of longs, null when absent/null, or null with recorded errors when invalid.
 */
internal fun parseOptionalLongSet(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): Set<Long>? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    if (element !is JsonArray) {
        validationErrors.add("Argument '$key' must be an array of integers")
        return null
    }
    val values = mutableSetOf<Long>()
    element.forEachIndexed { index, item ->
        val value = (item as? JsonPrimitive)?.longOrNull
        if (value == null) {
            validationErrors.add("Argument '$key[$index]' must be an integer")
        } else {
            values.add(value)
        }
    }
    return values
}

/**
 * Parses an optional ordered array-of-integers parameter.
 *
 * The order of the values is meaningful here — it is the order of the role's instruction list — and
 * duplicates are kept so the role validator can reject them with its own wording instead of the
 * argument silently collapsing. Absent and explicitly-`null` values both decode to `null`;
 * non-integer elements produce one validation error per offending index.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed list, null when absent/null, or null with recorded errors when invalid.
 */
internal fun parseOptionalLongList(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): List<Long>? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    if (element !is JsonArray) {
        validationErrors.add("Argument '$key' must be an array of integers")
        return null
    }
    val values = mutableListOf<Long>()
    element.forEachIndexed { index, item ->
        val value = (item as? JsonPrimitive)?.longOrNull
        if (value == null) {
            validationErrors.add("Argument '$key[$index]' must be an integer")
        } else {
            values.add(value)
        }
    }
    return values
}

/**
 * Builds the single invalid-input handler error from the accumulated validation errors.
 *
 * Every recorded issue is embedded in the message (one per line) so the LLM can fix them all at
 * once instead of iterating one error per turn.
 *
 * @param validationErrors The accumulated validation error list.
 * @return The [ServerBuiltInToolHandlerError.InvalidInput] carrying the combined message.
 */
internal fun invalidInputError(validationErrors: Collection<String>): ServerBuiltInToolHandlerError.InvalidInput {
    val message = buildString {
        append("Input validation failed with ${validationErrors.size} error(s):")
        validationErrors.forEach { append("\n- ").append(it) }
    }
    return ServerBuiltInToolHandlerError.InvalidInput(message)
}

/**
 * Parses an optional JSON-object parameter.
 *
 * Absent and explicitly-`null` values both decode to `null`. A present non-object value is a
 * validation error rather than being coerced, because the value is forwarded to a service that
 * would otherwise persist a wrong shape or report a failure about the wrong layer.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed object, null when absent/null, or null with a recorded error when invalid.
 */
internal fun parseOptionalJsonObject(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): JsonObject? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    if (element !is JsonObject) {
        validationErrors.add("Argument '$key' must be an object")
        return null
    }
    return element
}

/**
 * A single `oldText` -> `newText` replacement requested by `edit_instruction`.
 *
 * @property oldText Exact text to locate; blank values are rejected during parsing.
 * @property newText Replacement text.
 */
internal data class TextEditSpec(
    val oldText: String,
    val newText: String
)

/**
 * Parses the required `edits` batch of `edit_instruction`.
 *
 * Mirrors the worker `edit_file` validation: `edits` must be a non-empty array of objects each
 * carrying a non-blank string `oldText` and a string `newText`. Every malformed item records its
 * own error so the LLM sees all issues at once.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read (see
 *            [eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.EDITS_PROPERTY]).
 * @param validationErrors The accumulated validation error list.
 * @return The parsed edit specs, null when the array itself is missing or malformed. Valid items
 *         are still collected when sibling items are invalid; callers must check [validationErrors].
 */
internal fun parseEditSpecs(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): List<TextEditSpec>? {
    val element = input[key] ?: run {
        validationErrors.add("Missing required argument: $key")
        return null
    }
    if (element !is JsonArray) {
        validationErrors.add("Argument '$key' must be an array of {oldText, newText} objects")
        return null
    }
    if (element.isEmpty()) {
        validationErrors.add("At least one edit is required")
        return null
    }

    val edits = mutableListOf<TextEditSpec>()
    element.forEachIndexed { index, item ->
        if (item !is JsonObject) {
            validationErrors.add("Edit at index $index is not an object")
            return@forEachIndexed
        }

        val oldRaw = item[ServerBuiltInToolCatalog.OLD_TEXT_PROPERTY]
        val oldText = when {
            oldRaw == null -> {
                validationErrors.add("Edit at index $index missing 'oldText'")
                null
            }
            oldRaw !is JsonPrimitive || !oldRaw.isString -> {
                validationErrors.add("Edit at index $index: 'oldText' must be a string")
                null
            }
            oldRaw.content.isBlank() -> {
                validationErrors.add("Edit at index $index has empty or whitespace-only 'oldText'")
                null
            }
            else -> oldRaw.content
        }
        if (oldText == null) return@forEachIndexed

        val newRaw = item[ServerBuiltInToolCatalog.NEW_TEXT_PROPERTY]
        val newText = when {
            newRaw == null -> {
                validationErrors.add("Edit at index $index missing 'newText'")
                null
            }
            newRaw !is JsonPrimitive || !newRaw.isString -> {
                validationErrors.add("Edit at index $index: 'newText' must be a string")
                null
            }
            else -> newRaw.content
        }
        if (newText == null) return@forEachIndexed

        edits.add(TextEditSpec(oldText, newText))
    }
    return edits
}
