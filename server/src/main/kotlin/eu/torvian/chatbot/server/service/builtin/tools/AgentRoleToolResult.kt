package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.agent.AgentRoleDto

/**
 * Formats the concise, non-JSON operation summaries returned by the mutating agent-role tools.
 *
 * Mutating tools (`create_agent_role`, `update_agent_role`, `delete_agent_role`) deliberately do
 * **not** return the full [AgentRoleDto] JSON: instruction lists can be large, and echoing the whole
 * role after every mutation wastes tokens. Instead each tool returns a one-line plain-text description
 * of the operation it just completed: the action and the affected role's identity (name and id).
 * `read_agent_role` remains the tool that returns the full role.
 */

/**
 * Formats the summary for a completed `create_agent_role` operation.
 *
 * @param role The created role (as returned by the role service).
 * @return Plain text like `Created agent role 'writer' (id: 1).` (never JSON).
 */
internal fun formatCreatedAgentRole(role: AgentRoleDto): String =
    "Created agent role '${role.name}' (id: ${role.id})."

/**
 * Formats the summary for a completed `update_agent_role` operation.
 *
 * @param role The role state after the update (as returned by the role service).
 * @return Plain text like `Updated agent role 'writer' (id: 1).` (never JSON).
 */
internal fun formatUpdatedAgentRole(role: AgentRoleDto): String =
    "Updated agent role '${role.name}' (id: ${role.id})."

/**
 * Formats the summary for a completed `delete_agent_role` operation.
 *
 * The sweep outcome is part of the summary because it is unobservable afterwards: which linked rows
 * were removed with the role and which survive on other roles cannot be re-read once the role is
 * gone. Never-linked library rows are outside the role's link set and never appear.
 *
 * @param roleId The id of the deleted role (the delete service returns no role payload, so the
 *            message carries the id the caller supplied).
 * @param deletedInstructionIds Linked rows removed because no link remained; empty adds no clause.
 * @param retainedInstructionIds Linked rows kept because another role still links them; empty adds
 *            no clause.
 * @return Plain text like `Deleted agent role (id: 1); removed 1 instruction(s) that lost their last
 *         link (ids: 3).` or `Deleted agent role (id: 1).` (never JSON).
 */
internal fun formatDeletedAgentRole(
    roleId: Long,
    deletedInstructionIds: List<Long> = emptyList(),
    retainedInstructionIds: List<Long> = emptyList()
): String {
    val consequences = buildList {
        if (deletedInstructionIds.isNotEmpty()) {
            add(
                "removed ${deletedInstructionIds.size} instruction(s) that lost their last link " +
                    "(ids: ${deletedInstructionIds.joinToString(", ")})"
            )
        }
        if (retainedInstructionIds.isNotEmpty()) {
            add(
                "kept ${retainedInstructionIds.size} instruction(s) still linked by other role(s) " +
                    "(ids: ${retainedInstructionIds.joinToString(", ")})"
            )
        }
    }
    return if (consequences.isEmpty()) {
        "Deleted agent role (id: $roleId)."
    } else {
        "Deleted agent role (id: $roleId); ${consequences.joinToString("; ")}."
    }
}
