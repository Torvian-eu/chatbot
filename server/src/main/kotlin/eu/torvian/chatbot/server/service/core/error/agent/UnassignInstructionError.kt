package eu.torvian.chatbot.server.service.core.error.agent

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures of unlinking an instruction row from an agent role.
 *
 * Unlinking removes only the link: the instruction row survives as a library entry, so a foreign or
 * nonexistent row and a missing role are the only failures besides an absent link.
 */
sealed interface UnassignInstructionError {

    /**
     * The agent role to unlink from does not exist or is not owned by the requesting user.
     *
     * @property roleId The missing or foreign role identifier.
     */
    data class RoleNotFound(val roleId: Long) : UnassignInstructionError

    /**
     * The instruction row to unlink does not exist or is not owned by the requesting user.
     *
     * Missing and foreign ids collapse to the same error, so the request cannot tell an ownership
     * mismatch apart from a plain non-existent id (no existence leak).
     *
     * @property instructionId The missing or foreign instruction identifier.
     */
    data class InstructionNotFound(val instructionId: Long) : UnassignInstructionError

    /**
     * The role does not link the requested instruction row, so there is no link to remove.
     *
     * @property instructionId The instruction identifier that is not linked.
     */
    data class NotLinked(val instructionId: Long) : UnassignInstructionError
}

/**
 * Converts an [UnassignInstructionError] to its [ApiError] representation.
 */
fun UnassignInstructionError.toApiError(): ApiError = when (this) {
    is UnassignInstructionError.RoleNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Agent role not found", "roleId" to roleId.toString())

    is UnassignInstructionError.InstructionNotFound ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "Instruction not found",
            "instructionId" to instructionId.toString()
        )

    is UnassignInstructionError.NotLinked ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "The instruction is not linked to this agent role",
            "instructionId" to instructionId.toString()
        )
}
