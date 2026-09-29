package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for instruction ownership links.
 *
 * Instructions are per-user: an instruction has exactly one owner, mirroring the
 * `model_preset_owners`/`agent_role_owners` family (`instruction_id` is the primary key). Ownership
 * gates every content write and every role link, so one user can never observe or edit another
 * user's rows.
 *
 * @property instructionId Reference to the owned instruction (primary key, `CASCADE` on delete).
 * @property userId Reference to the owning user (`CASCADE` on delete).
 */
object InstructionOwnersTable : Table("instruction_owners") {
    val instructionId = reference("instruction_id", InstructionTable, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", UsersTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(instructionId)
}
