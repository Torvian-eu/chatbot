package eu.torvian.chatbot.app.service.api

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest

/**
 * API client interface for the user-owned instruction library.
 *
 * Instructions are shared content: an agent role references a row by id, so writing a row here reaches
 * every role that links it at the next read. Rows are owned per-user (the creating user becomes the
 * sole owner) and every endpoint is reachable with a `USER_JWT`. All methods return
 * [Either<ApiResourceError, T>] so callers handle failures explicitly.
 *
 * Rows are reported in the same shape a role payload uses ([AgentInstructionDto]), so a library row
 * also names the roles that link it.
 */
interface InstructionApi {

    /**
     * Lists every instruction owned by the current user, id ascending.
     *
     * Corresponds to `GET /api/v1/instructions`. Includes rows no agent role links any more, so the
     * caller can offer the whole library when one is needed. Every row names the roles that link it,
     * which is what lets a caller narrow the listing (by role, or to the unassigned rows) without a
     * second request.
     *
     * @return [Either.Right] containing the library rows on success, or [Either.Left] containing an
     *         [ApiResourceError] on failure.
     */
    suspend fun listInstructions(): Either<ApiResourceError, List<AgentInstructionDto>>

    /**
     * Reads one instruction owned by the current user, including the roles that link it.
     *
     * Corresponds to `GET /api/v1/instructions/{instructionId}`. A foreign or nonexistent row collapses
     * into the same not-found failure.
     *
     * @param instructionId The instruction to read.
     * @return [Either.Right] containing the library row on success, or [Either.Left] containing an
     *         [ApiResourceError] on failure.
     */
    suspend fun getInstruction(instructionId: Long): Either<ApiResourceError, AgentInstructionDto>

    /**
     * Creates a new instruction; the requesting user becomes its sole owner.
     *
     * Corresponds to `POST /api/v1/instructions` (201 on success). The created row links no role yet,
     * so it is reported with an empty linked-role set.
     *
     * @param request The authored content of the instruction to create.
     * @return [Either.Right] containing the created [AgentInstructionDto] on success, or [Either.Left]
     *         containing an [ApiResourceError] on failure.
     */
    suspend fun createInstruction(request: CreateInstructionRequest): Either<ApiResourceError, AgentInstructionDto>

    /**
     * Replaces the content of an existing instruction.
     *
     * Corresponds to `PUT /api/v1/instructions`. The update is a full replacement, not a patch, and it
     * reaches every agent role that links the row.
     *
     * @param request The replacement content, carrying the id of the row to update.
     * @return [Either.Right] containing the updated [AgentInstructionDto] on success, or [Either.Left]
     *         containing an [ApiResourceError] on failure.
     */
    suspend fun updateInstruction(request: UpdateInstructionRequest): Either<ApiResourceError, AgentInstructionDto>

    /**
     * Deletes an instruction owned by the current user, provided no agent role links it.
     *
     * Corresponds to `DELETE /api/v1/instructions/{instructionId}` (204 on success). A row that any
     * role still links is refused with 409 `resource-in-use`, naming the blocking role ids; the row is
     * deletable only once it is unassigned from every role. A foreign or nonexistent row collapses
     * into the same not-found failure.
     *
     * @param instructionId The instruction to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] containing an [ApiResourceError]
     *         on failure.
     */
    suspend fun deleteInstruction(instructionId: Long): Either<ApiResourceError, Unit>

    /**
     * Links an instruction to an agent role, appending it last in that role's instruction list.
     *
     * Corresponds to `POST /api/v1/agent-roles/{roleId}/instructions/{instructionId}`. The call is a
     * role mutation — it changes the role's ordered list — and answers with the updated role, so the
     * caller sees the new order and which roles link each entry.
     *
     * @param roleId The role to link to.
     * @param instructionId The instruction to link.
     * @return [Either.Right] containing the updated [AgentRoleDto] on success, or [Either.Left]
     *         containing an [ApiResourceError] on failure.
     */
    suspend fun assignInstruction(roleId: Long, instructionId: Long): Either<ApiResourceError, AgentRoleDto>

    /**
     * Removes an instruction's link from an agent role.
     *
     * Corresponds to `DELETE /api/v1/agent-roles/{roleId}/instructions/{instructionId}`. Only the link
     * is removed: the instruction row survives as a library entry. The answer is the updated role.
     *
     * @param roleId The role to unlink from.
     * @param instructionId The instruction to unlink.
     * @return [Either.Right] containing the updated [AgentRoleDto] on success, or [Either.Left]
     *         containing an [ApiResourceError] on failure.
     */
    suspend fun unassignInstruction(roleId: Long, instructionId: Long): Either<ApiResourceError, AgentRoleDto>
}
