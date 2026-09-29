package eu.torvian.chatbot.server.service.builtin.tools

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto

/**
 * Formats the concise, non-JSON operation summary returned by `delete_instruction`.
 *
 * The tool deliberately does not echo the deleted row: the caller only needs the confirmation, and a
 * linked row is never deleted (it fails with an in-use error naming the roles instead). `read_instruction`
 * and `create_instruction` return full JSON instead, because both answer a question about content that
 * the caller does not hold yet.
 */

/**
 * Formats the summary for a completed `delete_instruction` operation.
 *
 * @param instruction The row as read before the delete: it supplies the label and the id (the delete
 *            itself returns no payload).
 * @return Plain text like `Deleted instruction 'Project rules' (id: 7).` (never JSON).
 */
internal fun formatDeletedInstruction(instruction: AgentInstructionDto): String =
    "Deleted instruction '${instruction.name}' (id: ${instruction.id})."