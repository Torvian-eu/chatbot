package eu.torvian.chatbot.common.models.tool.specs

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.NEW_TEXT_PROPERTY
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog.OLD_TEXT_PROPERTY
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds an empty-object input schema (no parameters).
 *
 * A bare `{}` would fail tool validation, which requires a `type` or `properties` key, so the
 * empty shape carries `type: object` plus an empty `properties` map.
 *
 * @return The JSON Schema for a parameterless tool call.
 */
internal fun emptyObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {})
}

/**
 * Builds a JSON Schema for an integer property.
 *
 * @param description Human-readable description of the property.
 * @param minimum Optional inclusive lower bound, used for row ids so a placeholder `0` is
 *            rejected by the schema instead of reaching the lookup.
 * @return The JSON Schema object for the integer property.
 */
internal fun integerProperty(description: String, minimum: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    if (minimum != null) put("minimum", minimum)
    put("description", description)
}

/**
 * Builds a JSON Schema for the instruction-kind property of an authored instruction.
 *
 * The `enum` is derived from [AgentInstructionTypes.allKnown] so the accepted kinds cannot
 * drift from the server's validation of the same value.
 *
 * @return The JSON Schema object for the `type` property.
 */
internal fun instructionTypeProperty(): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", "Kind of the instruction; one of the known instruction kinds.")
    putJsonArray("enum") {
        AgentInstructionTypes.allKnown.forEach { add(it) }
    }
}

/**
 * Builds a JSON Schema for a free-form JSON-object property.
 *
 * The object is deliberately open: its keys depend on the instruction kind, and the server
 * validates the kind-specific requirements, so a schema change cannot weaken them.
 *
 * @param description Human-readable description of the property.
 * @return The JSON Schema object for the object-valued property.
 */
internal fun objectProperty(description: String): JsonObject = buildJsonObject {
    put("type", "object")
    put("description", description)
}

/**
 * Builds a JSON Schema for the text-edit batch of `edit_instruction`.
 *
 * Mirrors the worker `edit_file` tool's `edits` parameter shape: an array of `oldText`/`newText`
 * pairs matched exactly against the original instruction message (array order is not sequential,
 * and all non-overlapping occurrences of each `oldText` are replaced).
 *
 * @return The JSON Schema object for the `edits` array.
 */
internal fun editsProperty(): JsonObject = buildJsonObject {
    put("type", "array")
    put("minItems", 1)
    put(
        "description",
        "Replacement batch matched against the original instruction message; array order is " +
            "not sequential. Each edit replaces all non-overlapping occurrences of its oldText, " +
            "and the operation fails if an oldText matches nothing."
    )
    putJsonObject("items") {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject(OLD_TEXT_PROPERTY) {
                put("type", "string")
                put(
                    "description",
                    "Exact text to replace. All non-overlapping occurrences are replaced; add " +
                        "surrounding context to target one occurrence."
                )
            }
            putJsonObject(NEW_TEXT_PROPERTY) {
                put("type", "string")
                put("description", "Replacement text.")
            }
        }
        putJsonArray("required") {
            add(OLD_TEXT_PROPERTY)
            add(NEW_TEXT_PROPERTY)
        }
    }
}

/**
 * Builds a JSON Schema for an optional string property.
 *
 * @param description Human-readable description of the property.
 * @return The JSON Schema object for the string property.
 */
internal fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/**
 * Builds the JSON Schema for a boolean property.
 *
 * @param description Human-readable description of the property.
 * @return The JSON Schema object for the boolean property.
 */
internal fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

/**
 * Builds a JSON Schema for an array-of-integers property.
 *
 * @param description Human-readable description of the property.
 * @return The JSON Schema object for the integer-array property.
 */
internal fun integerArrayProperty(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject {
        put("type", "integer")
    })
}

/**
 * Builds a JSON Schema for the ordered instruction-id list property.
 *
 * A role references instructions by id: the values select existing instruction rows, and their
 * order is the order of the role's instruction list. Instruction content is authored through the
 * instruction surfaces, so the schema carries no nested instruction objects.
 *
 * @param create True when building the schema for `create_agent_role`, false for `update_agent_role`.
 * @return The JSON Schema object for the `instruction_ids` array.
 */
internal fun instructionIdsProperty(create: Boolean): JsonObject = buildJsonObject {
    put("type", "array")
    put(
        "description",
        if (create) {
            "Optional ordered ids of existing instructions that make up the role's system " +
                "prompt. Every id must reference an instruction owned by the current user, and the " +
                "same instruction can be linked at most once. Omit to create a role without " +
                "instructions."
        } else {
            "New ordered ids of the instructions that make up the role's system prompt (full " +
                "replacement of the role's instruction list). Every id must reference an " +
                "instruction owned by the current user, and the same instruction can be linked at " +
                "most once. Omit to keep the role's current instructions."
        }
    )
    put("items", buildJsonObject {
        put("type", "integer")
        put("minimum", 1)
    })
}
