package eu.torvian.chatbot.server.service.core.error.agent

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError

/**
 * Logical failures of linking an instruction row to an agent role.
 *
 * Linking mutates the role's ordered instruction list, so the failures mirror the role-write
 * instruction checks: the role and the row must both be owned, the pair must not be linked yet, and
 * the resulting list must satisfy the per-role instruction rules.
 */
sealed interface AssignInstructionError {

    /**
     * The agent role to link to does not exist or is not owned by the requesting user.
     *
     * @property roleId The missing or foreign role identifier.
     */
    data class RoleNotFound(val roleId: Long) : AssignInstructionError

    /**
     * The instruction row to link does not exist or is not owned by the requesting user.
     *
     * Missing and foreign ids collapse to the same error, so the request cannot tell an ownership
     * mismatch apart from a plain non-existent id (no existence leak).
     *
     * @property instructionId The missing or foreign instruction identifier.
     */
    data class InstructionNotFound(val instructionId: Long) : AssignInstructionError

    /**
     * The role already links the requested instruction row.
     *
     * @property instructionId The already-linked instruction identifier.
     */
    data class AlreadyLinked(val instructionId: Long) : AssignInstructionError

    /**
     * Appending the row would violate the agent-role instruction rules (e.g. a second `role` row).
     *
     * @property reason Human-readable explanation of the validation failure.
     */
    data class InstructionValidationFailed(val reason: String) : AssignInstructionError
}

/**
 * Converts an [AssignInstructionError] to its [ApiError] representation.
 */
fun AssignInstructionError.toApiError(): ApiError = when (this) {
    is AssignInstructionError.RoleNotFound ->
        apiError(CommonApiErrorCodes.NOT_FOUND, "Agent role not found", "roleId" to roleId.toString())

    is AssignInstructionError.InstructionNotFound ->
        apiError(
            CommonApiErrorCodes.NOT_FOUND,
            "Instruction not found",
            "instructionId" to instructionId.toString()
        )

    is AssignInstructionError.AlreadyLinked ->
        apiError(
            CommonApiErrorCodes.ALREADY_EXISTS,
            "The instruction is already linked to this agent role",
            "instructionId" to instructionId.toString()
        )

    is AssignInstructionError.InstructionValidationFailed ->
        apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid agent role instructions: $reason")
}
