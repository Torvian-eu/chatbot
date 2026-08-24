package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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
 * Parses an optional array-of-integers parameter.
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
 * Parses an optional instruction list (advanced usage).
 *
 * Each element must decode to an [AgentInstructionDto]; malformed arrays produce a readable
 * validation error instead of a crash.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param json The shared JSON codec used for decoding.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed instruction list, null when absent/null, or null with a recorded error when
 *         the array does not decode.
 */
internal fun parseOptionalInstructions(
    input: JsonObject,
    key: String,
    json: Json,
    validationErrors: MutableList<String>,
): List<AgentInstructionDto>? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    return runCatching {
        json.decodeFromJsonElement<List<AgentInstructionDto>>(element)
    }.getOrElse { error ->
        validationErrors.add("Argument '$key' must be an array of instruction objects: ${error.message}")
        null
    }
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
