package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentRoleDto

/**
 * Formats the concise, non-JSON operation summaries returned by the mutating agent-role tools.
 *
 * Mutating tools (`create_agent_role`, `update_agent_role`, `insert_agent_role_instruction`,
 * `edit_agent_role_instructions`, `remove_agent_role_instruction`) deliberately do **not** return
 * the full [AgentRoleDto] JSON: instruction lists can be large, and echoing the whole role after
 * every mutation wastes tokens. Instead each tool returns a one-line plain-text description of the
 * operation it just completed: the action, the affected role's identity (name and id), and — for
 * the instruction tools — the affected instruction's type/name and its zero-based position.
 * `read_agent_role` remains the tool that returns the full role; `edit_agent_role_instructions`
 * additionally appends its diff report.
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
 * Formats the summary for a completed `insert_agent_role_instruction` operation.
 *
 * @param role The role state after the insert (as returned by the role service).
 * @param position The zero-based position the new instruction was inserted at.
 * @param instruction The inserted instruction, whose type and name identify it for the LLM.
 * @return Plain text like `Inserted instruction (type=custom, name=Tone) at 0-based position 1
 *         in agent role 'writer' (id: 1).` (never JSON).
 */
internal fun formatInsertedInstruction(
    role: AgentRoleDto,
    position: Int,
    instruction: AgentInstructionDto
): String =
    "Inserted instruction (type=${instruction.type}, name=${instruction.name}) at 0-based " +
        "position $position in agent role '${role.name}' (id: ${role.id})."

/**
 * Formats the summary for a completed `remove_agent_role_instruction` operation.
 *
 * @param role The role state after the removal (as returned by the role service).
 * @param position The zero-based position the instruction was removed from.
 * @param instruction The removed instruction, whose type and name identify it for the LLM.
 * @return Plain text like `Removed instruction (type=custom, name=Style) at 0-based position 1
 *         from agent role 'writer' (id: 1).` (never JSON).
 */
internal fun formatRemovedInstruction(
    role: AgentRoleDto,
    position: Int,
    instruction: AgentInstructionDto
): String =
    "Removed instruction (type=${instruction.type}, name=${instruction.name}) at 0-based " +
        "position $position from agent role '${role.name}' (id: ${role.id})."
