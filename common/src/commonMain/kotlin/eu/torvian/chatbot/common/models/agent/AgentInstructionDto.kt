package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Flat wire contract for a single stored instruction row as the server reports it.
 *
 * The same shape serves both report sites: the instruction list of an agent-role payload (or role
 * tool result), and the caller's instruction library. It is a simple data class — no polymorphism, no
 * sealed subtypes, no `SerializersModule` registration needed.
 *
 * Identity comes first because every reported instruction is backed by a stored row: [id] is always
 * present, whether one agent role links the row or many, and [linkedRoleIds] lists the roles that link
 * it. Writing happens elsewhere: instructions are authored through
 * [eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest] and
 * [eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest], while a role write
 * only selects rows through its ordered instruction ids.
 *
 * [type] identifies the instruction kind (see [AgentInstructionTypes]). Type-specific fields (e.g.
 * `modelId` for `model_specific`) are stored in [custom] as a [JsonObject], keeping the DTO flat
 * while allowing per-kind extension.
 *
 * @property id Identifier of the stored instruction row, always set.
 * @property type The [AgentInstructionTypes] key of this instruction kind.
 * @property name Human-readable label of the instruction.
 * @property message Instruction text. Reported for a role, [AgentInstructionTypes.SPAWNABLE_AGENTS]
 *            and other dynamic kinds carry the text generated for that role, while static kinds carry
 *            the stored value. Reported as a library row there is no role to generate for, so the
 *            value is the stored text and empty for a kind whose text is never stored.
 * @property custom Type-specific extra fields (e.g. `{"modelId": 5}` for `model_specific`); null for
 *            kinds that carry no extra data.
 * @property linkedRoleIds Ids of the agent roles that link the row, unordered and duplicate-free
 *            (iteration order is ascending for stable payloads).
 */
@Serializable
data class AgentInstructionDto(
    val id: Long,
    val type: String,
    val name: String,
    val message: String,
    val custom: JsonObject? = null,
    val linkedRoleIds: Set<Long> = emptySet()
)

/**
 * Whether the instruction content is shared by more than one agent role.
 *
 * Derived from [AgentInstructionDto.linkedRoleIds] rather than serialized: the wire format carries
 * only the role ids, so every consumer computes the flag the same way.
 *
 * @receiver The instruction to inspect.
 * @return `true` when more than one agent role links the instruction row.
 */
val AgentInstructionDto.shared: Boolean get() = linkedRoleIds.size > 1

/**
 * Extracts the `modelId` target from the [AgentInstructionDto.custom] JSON of a `model_specific` instruction.
 *
 * Safely returns null when the field is absent, not a primitive, or not a valid long — avoiding
 * the `IllegalArgumentException` that `.jsonPrimitive` / `.long` would throw on malformed data.
 * Callers that require a value should use `?: error(...)` rather than relying on the exception.
 *
 * @receiver The instruction whose `custom` JSON may contain a `modelId`.
 * @return The model id stored in `custom["modelId"]`, or null if not present or not a parsable long.
 */
fun AgentInstructionDto.modelSpecificId(): Long? = custom.modelIdOrNull()

/**
 * Extracts a `modelId` value from a raw `custom` JSON object.
 *
 * Same leniency rules as [modelSpecificId]: malformed or absent values yield null instead of
 * throwing, so stored rows with corrupt `custom` text degrade gracefully at read time.
 *
 * @receiver The `custom` JSON object, or null when the instruction carries none.
 * @return The model id stored in `custom["modelId"]`, or null if not present or not a parsable long.
 */
fun JsonObject?.modelIdOrNull(): Long? =
    (this?.get("modelId") as? JsonPrimitive)?.longOrNull
