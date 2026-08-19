package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Flat wire contract for a single agent-role instruction.
 *
 * This is the shape an instruction takes when it crosses the API boundary or is persisted in the
 * `agent_roles.instructions_json` column. It is a simple data class — no polymorphism, no sealed
 * subtypes, no `SerializersModule` registration needed.
 *
 * [type] identifies the instruction kind (see [AgentInstructionTypes]). Type-specific fields (e.g.
 * `modelId` for `model_specific`) are stored in [custom] as a [JsonObject], keeping the DTO flat
 * while allowing per-kind extension.
 *
 * @property type The [AgentInstructionTypes] key of this instruction kind.
 * @property name Human-readable label of the instruction.
 * @property message Resolved instruction text. For [AgentInstructionTypes.MODEL_SETTINGS] the server
 *            sends the referenced settings' system text, for
 *            [AgentInstructionTypes.SPAWNABLE_AGENTS] generated role guidance, and for static kinds
 *            the stored value itself.
 * @property custom Type-specific extra fields (e.g. `{"modelId": 5}` for `model_specific`); null for
 *            kinds that carry no extra data.
 */
@Serializable
data class AgentInstructionDto(
    val type: String,
    val name: String,
    val message: String,
    val custom: JsonObject? = null
)

/**
 * Extracts the `modelId` target from the [custom] JSON of a `model_specific` instruction.
 *
 * Safely returns null when the field is absent, not a primitive, or not a valid long — avoiding
 * the `IllegalArgumentException` that `.jsonPrimitive` / `.long` would throw on malformed data.
 * Callers that require a value should use `?: error(...)` rather than relying on the exception.
 *
 * @receiver The instruction whose `custom` JSON may contain a `modelId`.
 * @return The model id stored in `custom["modelId"]`, or null if not present or not a parsable long.
 */
fun AgentInstructionDto.modelSpecificId(): Long? =
    (custom?.get("modelId") as? JsonPrimitive)?.longOrNull
