package eu.torvian.chatbot.worker.builtin.validation

import eu.torvian.chatbot.common.models.api.worker.protocol.payload.BuiltInToolExecutionResult
import eu.torvian.chatbot.worker.builtin.BuiltInToolExecutionError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * Creates a standardized [BuiltInToolExecutionResult] for a tool execution error.
 *
 * @param code The error code string identifying the failure category.
 * @param message The error message describing the failure.
 * @param errorDetails Optional JSON string containing detailed error information.
 * @return A tool execution result indicating execution failure.
 */
internal fun builtInToolErrorResult(
    code: String,
    message: String,
    errorDetails: String? = null,
): BuiltInToolExecutionResult =
    BuiltInToolExecutionResult(
        isError = true,
        errorCode = code,
        errorMessage = message,
        errorDetails = errorDetails,
    )

/**
 * Creates a standardized [BuiltInToolExecutionResult] for accumulated input validation errors.
 *
 * @param validationErrors The collection of validation error messages.
 * @return A tool execution result indicating input validation failure with error details.
 */
internal fun invalidInputResult(validationErrors: Collection<String>): BuiltInToolExecutionResult =
    builtInToolErrorResult(
        code = BuiltInToolExecutionError.INVALID_INPUT,
        message = "Input validation failed with ${validationErrors.size} error(s):",
        errorDetails = buildJsonObject {
            putJsonArray("validationErrors") {
                validationErrors.forEach { add(it) }
            }
        }.toString()
    )

/**
 * Adds validation errors for parameters that are not part of the accepted tool input shape.
 *
 * @param input The raw tool input object.
 * @param validKeys The set of accepted parameter names.
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
 * Parses an optional string parameter, recording a validation error when the value is present but invalid.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed string value, or null when the parameter is absent or invalid.
 */
internal fun parseOptionalString(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): String? {
    val element = input[key] ?: return null
    if (element !is JsonPrimitive || !element.isString) {
        validationErrors.add("Argument '$key' must be a string")
        return null
    }
    return element.content
}

/**
 * Parses an optional boolean parameter with a default value.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param defaultValue The value to use when the parameter is absent or invalid.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed boolean value, or [defaultValue] when the parameter is absent or invalid.
 */
internal fun parseOptionalBoolean(
    input: JsonObject,
    key: String,
    defaultValue: Boolean,
    validationErrors: MutableList<String>,
): Boolean {
    val element = input[key] ?: return defaultValue
    if (element !is JsonPrimitive) {
        validationErrors.add("Argument '$key' must be a boolean ('true' or 'false')")
        return defaultValue
    }
    val raw = element.content
    return raw.toBooleanStrictOrNull() ?: run {
        validationErrors.add("Argument '$key' must be a boolean ('true' or 'false')")
        defaultValue
    }
}

/**
 * Parses an optional integer parameter with a default value.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param defaultValue The value to use when the parameter is absent or invalid.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed integer value, or [defaultValue] when the parameter is absent or invalid.
 */
internal fun parseOptionalInt(
    input: JsonObject,
    key: String,
    defaultValue: Int,
    validationErrors: MutableList<String>,
): Int {
    val element = input[key] ?: return defaultValue
    if (element !is JsonPrimitive) {
        validationErrors.add("Argument '$key' must be an integer")
        return defaultValue
    }
    val raw = element.content
    return raw.toIntOrNull() ?: run {
        validationErrors.add("Argument '$key' must be an integer")
        defaultValue
    }
}

/**
 * Parses an optional nullable integer parameter.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed integer value, or null when the parameter is absent or invalid.
 */
internal fun parseOptionalIntOrNull(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): Int? {
    val element = input[key] ?: return null
    if (element !is JsonPrimitive) {
        validationErrors.add("Argument '$key' must be an integer")
        return null
    }
    val raw = element.content
    return raw.toIntOrNull() ?: run {
        validationErrors.add("Argument '$key' must be an integer")
        null
    }
}

/**
 * Parses an optional long parameter with a default value.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param defaultValue The value to use when the parameter is absent or invalid.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed long value, or [defaultValue] when the parameter is absent or invalid.
 */
internal fun parseOptionalLong(
    input: JsonObject,
    key: String,
    defaultValue: Long,
    validationErrors: MutableList<String>,
): Long {
    val element = input[key] ?: return defaultValue
    if (element !is JsonPrimitive) {
        validationErrors.add("Argument '$key' must be an integer")
        return defaultValue
    }
    val raw = element.content
    return raw.toLongOrNull() ?: run {
        validationErrors.add("Argument '$key' must be an integer")
        defaultValue
    }
}

/**
 * Parses a parameter that accepts either a single string or an array of strings.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed string values, or an empty list when the parameter is absent or invalid.
 */
internal fun parseStringOrStringArray(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): List<String> {
    val element = input[key] ?: return emptyList()
    return when (element) {
        is JsonPrimitive -> {
            if (!element.isString) {
                validationErrors.add("Argument '$key' must be a string or array of strings")
                emptyList()
            } else {
                listOf(element.content)
            }
        }

        is JsonArray -> buildList {
            element.forEachIndexed { index, item ->
                if (item is JsonPrimitive && item.isString) {
                    add(item.content)
                } else {
                    validationErrors.add("Argument '$key[$index]' must be a string")
                }
            }
        }

        else -> {
            validationErrors.add("Argument '$key' must be a string or array of strings")
            emptyList()
        }
    }
}
