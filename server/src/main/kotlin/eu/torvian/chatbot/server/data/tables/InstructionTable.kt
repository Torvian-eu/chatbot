package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Exposed table definition for shareable agent-role instructions.
 *
 * One row holds one instruction's content (`type`, `name`, `message`, `custom`), independent of the
 * agent roles that use it; the ordered role links live in [AgentRoleInstructionTable] and the single
 * owner in [InstructionOwnersTable]. `message` is NULL exactly for generated kinds
 * (`spawnable_agents`), whose text is produced per linked role at read time from that role's current
 * spawn allow-list and tool set.
 *
 * @property type The instruction kind key (one of the well-known `AgentInstructionTypes` values;
 *            unknown kinds are dropped with a warning at read time).
 * @property name Human-readable label of the instruction.
 * @property message Stored instruction text; null means "generated at read time".
 * @property custom Raw JSON text of type-specific fields (e.g. `{"modelId": 5}`); null when the kind
 *            carries no extra data.
 * @property createdAt Timestamp when the instruction was created.
 * @property updatedAt Timestamp when the instruction content was last changed.
 */
object InstructionTable : LongIdTable("instructions") {
    val type = varchar("type", 64)
    val name = varchar("name", 255)
    val message = text("message").nullable()
    val custom = text("custom").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}
