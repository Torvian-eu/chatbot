package eu.torvian.chatbot.server.service.builtin

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Each array element is parsed field-by-field (see [parseInstructionObject]) instead of being
 * decoded through kotlinx serialization, so malformed items produce readable, item-indexed
 * validation errors rather than raw serialization exception text. Every malformed item records
 * its own error, prefixed with its array index, so the LLM sees all issues at once.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed instruction list, null when absent/null, or null with a recorded error when
 *         the array does not parse.
 */
internal fun parseOptionalInstructions(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): List<AgentInstructionDto>? {
    val element = input[key] ?: return null
    if (element == JsonNull) return null
    if (element !is JsonArray) {
        validationErrors.add("Argument '$key' must be an array of instruction objects")
        return null
    }

    val errorsBefore = validationErrors.size
    val instructions = mutableListOf<AgentInstructionDto>()
    element.forEachIndexed { index, item ->
        if (item !is JsonObject) {
            validationErrors.add("Argument '$key' item $index must be an object")
            return@forEachIndexed
        }
        // Parse each item into its own error list so the reported issues carry the item index.
        val itemErrors = mutableListOf<String>()
        val parsed = parseInstructionObject(item, itemErrors)
        if (parsed == null) {
            itemErrors.forEach { validationErrors.add("Argument '$key' item $index: $it") }
        } else {
            instructions.add(parsed)
        }
    }
    if (validationErrors.size != errorsBefore) return null
    return instructions
}

/**
 * A single `oldText` -> `newText` replacement requested by `edit_agent_role_instructions`.
 *
 * @property oldText Exact text to locate; blank values are rejected during parsing.
 * @property newText Replacement text.
 */
internal data class TextEditSpec(
    val oldText: String,
    val newText: String
)

/**
 * Parses the required `edits` batch of `edit_agent_role_instructions`.
 *
 * Mirrors the worker `edit_file` validation: `edits` must be a non-empty array of objects each
 * carrying a non-blank string `oldText` and a string `newText`. Every malformed item records its
 * own error so the LLM sees all issues at once.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read (see [eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.EDITS_PROPERTY]).
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

/**
 * Parses the required single-instruction parameter of `insert_agent_role_instruction`.
 *
 * Reads `type`, `name`, `message`, and the optional `custom` from the object and builds an
 * [AgentInstructionDto]. `message` is required for every kind; a `spawnable_agents` instruction
 * takes an empty string (the server regenerates the message from the role's spawn allow-list).
 * `custom` is optional; when present it must be a JSON object.
 *
 * @param input The raw tool input object.
 * @param key The parameter name to read (see [eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.INSTRUCTION_PROPERTY]).
 * @param validationErrors The accumulated validation error list.
 * @return The parsed [AgentInstructionDto], or null (with recorded errors) when any sub-field is
 *         missing or malformed.
 */
internal fun parseRequiredInstruction(
    input: JsonObject,
    key: String,
    validationErrors: MutableList<String>,
): AgentInstructionDto? {
    val element = input[key] ?: run {
        validationErrors.add("Missing required argument: $key")
        return null
    }
    if (element !is JsonObject) {
        validationErrors.add("Argument '$key' must be an object")
        return null
    }
    return parseInstructionObject(element, validationErrors)
}

/**
 * Parses one instruction object into an [AgentInstructionDto], validating every sub-field.
 *
 * Shared by the single-instruction parameter of `insert_agent_role_instruction` and the
 * `instructions` array of `create_agent_role`/`update_agent_role`. `type`, `name`, and `message`
 * are required (a `spawnable_agents` instruction takes an empty message); `custom` is optional
 * and must be an object when present; unknown keys inside the object are rejected (the catalog
 * schema advertises `additionalProperties: false`). Any sub-field failure records its own readable
 * error and yields null, so callers can abort via the accumulated validation errors.
 *
 * @param element The instruction object to parse.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed [AgentInstructionDto], or null with recorded errors when any sub-field is
 *         missing or malformed.
 */
private fun parseInstructionObject(
    element: JsonObject,
    validationErrors: MutableList<String>,
): AgentInstructionDto? {
    // Record the error count before parsing sub-fields so any new error means the instruction
    // cannot be built and the caller must bail out via the accumulated validation errors.
    val errorsBefore = validationErrors.size

    // The catalog schema advertises additionalProperties: false for the instruction object, so a
    // stray key is rejected instead of being silently dropped by the DTO (mirrors the top-level
    // addUnknownParameterErrors check). A typo like "custom_properties" therefore surfaces to the
    // LLM rather than vanishing from the persisted role.
    val knownKeys = setOf("type", "name", "message", "custom")
    element.keys.filterNot { it in knownKeys }.forEach { key ->
        validationErrors.add("Unknown parameter: '$key' in instruction object")
    }

    val type = parseInstructionType(element, validationErrors)
    val name = parseRequiredString(element, "name", validationErrors)
    val message = parseInstructionMessage(element, validationErrors)
    val custom = parseOptionalJsonObject(element, "custom", validationErrors)

    if (validationErrors.size != errorsBefore) return null
    return AgentInstructionDto(
        type = requireNotNull(type),
        name = requireNotNull(name),
        message = requireNotNull(message),
        custom = custom
    )
}

/**
 * Parses and validates the `type` field of an instruction object.
 *
 * The value must be one of the well-known [AgentInstructionTypes] kinds; unknown kinds are
 * rejected up front because the server would silently drop them at role-read time.
 *
 * @param input The instruction object.
 * @param validationErrors The accumulated validation error list.
 * @return The validated type string, or null with a recorded error.
 */
private fun parseInstructionType(
    input: JsonObject,
    validationErrors: MutableList<String>,
): String? {
    val element = input["type"] ?: run {
        validationErrors.add("Missing required argument: type")
        return null
    }
    if (element !is JsonPrimitive || !element.isString) {
        validationErrors.add("Argument 'type' must be a string")
        return null
    }
    val value = element.content
    if (value !in AgentInstructionTypes.allKnown) {
        validationErrors.add(
            "Argument 'type' must be one of: ${AgentInstructionTypes.allKnown.joinToString(", ")}"
        )
        return null
    }
    return value
}

/**
 * Parses the `message` field of an instruction object.
 *
 * `message` is required for every instruction kind and must be a string (never null). A
 * `spawnable_agents` instruction takes an empty string: the server regenerates the message from
 * the role's spawn allow-list at read time.
 *
 * @param input The instruction object.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed message, or null with a recorded error.
 */
private fun parseInstructionMessage(
    input: JsonObject,
    validationErrors: MutableList<String>,
): String? {
    val element = input["message"] ?: run {
        validationErrors.add("Missing required argument: message")
        return null
    }
    if (element == JsonNull) {
        validationErrors.add(
            "Argument 'message' must be a string (null is not allowed; for a " +
                "spawnable_agents instruction pass an empty string)"
        )
        return null
    }
    if (element !is JsonPrimitive || !element.isString) {
        validationErrors.add("Argument 'message' must be a string")
        return null
    }
    return element.content
}

/**
 * Parses an optional JSON-object field.
 *
 * Absent and explicitly-`null` values both decode to null; any other non-object value is a
 * validation error.
 *
 * @param input The containing object.
 * @param key The field name to read.
 * @param validationErrors The accumulated validation error list.
 * @return The parsed [JsonObject], or null when absent/null, or null with a recorded error.
 */
private fun parseOptionalJsonObject(
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
