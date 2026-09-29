package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for the ordered agent-role ↔ instruction join.
 *
 * - The composite primary key `(agent_role_id, instruction_id)` makes duplicate links impossible at
 *   the DB level (the same instruction row may appear at most once per role).
 * - `sequence` holds the instruction's zero-based position in the role's list; role writes rewrite
 *   the links contiguously, so ordering survives full replacements and deletions.
 * - `ON DELETE CASCADE` on the role reference: deleting a role removes its link rows, and the
 *   role-deletion service sweep then removes instruction rows left with no links at all (a row with
 *   any surviving link stays).
 * - `ON DELETE RESTRICT` on the instruction reference: a linked instruction row cannot be deleted,
 *   so its links never disappear behind the caller's back. The service refuses the delete while any
 *   role links the row; this constraint is the storage-level backstop for that rule.
 *
 * @property agentRoleId Reference to the linking agent role (`CASCADE` on delete).
 * @property instructionId Reference to the linked instruction (`RESTRICT` on delete).
 * @property sequence Zero-based position of the instruction within the role's ordered list.
 */
object AgentRoleInstructionTable : Table("agent_role_instructions") {
    val agentRoleId = reference("agent_role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)
    val instructionId = reference("instruction_id", InstructionTable, onDelete = ReferenceOption.RESTRICT)
    val sequence = integer("sequence")

    override val primaryKey = PrimaryKey(agentRoleId, instructionId)

    init {
        // Reverse lookups resolve which roles link a given instruction (shared computation, delete
        // sweeps); the composite PK already covers the role-side direction.
        index(isUnique = false, instructionId)
    }
}
