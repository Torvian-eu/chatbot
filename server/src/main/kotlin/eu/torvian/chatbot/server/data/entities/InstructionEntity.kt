package eu.torvian.chatbot.server.data.entities

import kotlin.time.Instant

/**
 * Represents a row from the `instructions` database table.
 *
 * A plain content projection: the owning user lives in `instruction_owners` and the linking roles in
 * `agent_role_instructions`, both loaded separately. [message] is null exactly for generated kinds
 * (`spawnable_agents`), whose text is resolved per linked role at read time; [custom] keeps the raw
 * JSON text of type-specific fields so the entity never parses it.
 *
 * @property id Unique identifier for the instruction.
 * @property type The instruction kind key.
 * @property name Human-readable label of the instruction.
 * @property message Stored instruction text; null means "generated at read time".
 * @property custom Raw JSON text of type-specific fields; null when the kind carries no extra data.
 * @property createdAt Timestamp when the instruction was created.
 * @property updatedAt Timestamp when the instruction content was last changed.
 */
data class InstructionEntity(
    val id: Long,
    val type: String,
    val name: String,
    val message: String?,
    val custom: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
