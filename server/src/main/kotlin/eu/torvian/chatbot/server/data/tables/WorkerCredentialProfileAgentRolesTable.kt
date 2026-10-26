package eu.torvian.chatbot.server.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definition for the credential-profile ↔ agent-role join.
 *
 * - The primary key `(profile_id, agent_role_id)` makes a duplicate link impossible at the DB level.
 * - `ON DELETE CASCADE` on both foreign keys: deleting a profile or a role removes the links, so no
 *   dangling id can exist and no application-level sweep is needed.
 * - No ordering column: a profile's role set is deliberately unordered (it mirrors `Set<Long>` on the
 *   wire), so a full replacement is a plain delete + insert with no ordering bookkeeping.
 *
 * @property profileId Reference to the linked credential profile (`CASCADE` on delete).
 * @property agentRoleId Reference to the agent role allowed to attach it (`CASCADE` on delete).
 */
object WorkerCredentialProfileAgentRolesTable : Table("worker_credential_profile_agent_roles") {
    val profileId = reference("profile_id", WorkerCredentialProfileTable, onDelete = ReferenceOption.CASCADE)
    val agentRoleId = reference("agent_role_id", AgentRoleTable, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(profileId, agentRoleId)

    init {
        // SQLite does not auto-create FK-column indexes on the child side; this index makes the
        // ON DELETE CASCADE from agent_roles and reverse lookups ("which profiles use role X") fast.
        index(isUnique = false, agentRoleId)
    }
}
