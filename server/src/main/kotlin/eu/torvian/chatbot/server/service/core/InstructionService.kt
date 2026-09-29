package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.server.service.core.error.instruction.CreateInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.DeleteInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.GetInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.UpdateInstructionError

/**
 * User-scoped access to the instruction library.
 *
 * Instructions are first-class objects: agent-role payloads reference them by id only, so every flow
 * that authors, edits, lists, reads or deletes their content goes through this service. The creator
 * owns the created row and a missing or foreign id collapses to the same not-found error, so the
 * caller's error surface stays uniform and existence does not leak.
 *
 * Rows are reported as [AgentInstructionDto] — the same shape a role payload uses — so a library
 * consumer learns which roles link a row without a second request. A library row carries the stored
 * text, because no role context exists to generate dynamic text from.
 */
interface InstructionService {

    /**
     * Lists every instruction row owned by [userId].
     *
     * Includes rows that no agent role links any more: dropping the last link keeps the row as an
     * unassigned library entry. Listing has no logical failure mode, so it reports rows directly
     * instead of an [Either].
     *
     * Every row carries its linking roles, so a consumer that wants to narrow the listing (by role,
     * or to the unassigned rows) filters the delivered rows itself instead of requesting a selection
     * the server would have to re-read.
     *
     * @param userId User whose library is listed.
     * @return The user's library rows ordered by row id ascending (an empty list when none exist).
     */
    suspend fun getAllInstructionsForUser(userId: Long): List<AgentInstructionDto>

    /**
     * Reads one instruction row owned by [userId], reporting the roles that link it.
     *
     * The reported [AgentInstructionDto.linkedRoleIds] is the usage information a caller needs to
     * tell shared content from role-specific content; resolving those ids into role labels is left to
     * the caller, which already holds the role catalog it displays.
     *
     * @param userId User whose ownership of the row is required.
     * @param instructionId The row to read.
     * @return Either a [GetInstructionError] or the requested library row.
     */
    suspend fun getInstructionById(userId: Long, instructionId: Long): Either<GetInstructionError, AgentInstructionDto>

    /**
     * Creates an instruction row owned by [userId].
     *
     * The new row is linked to nothing yet, so it is reported with no linking roles.
     *
     * @param userId User who becomes the owner of the new row.
     * @param request The authored content.
     * @return Either a [CreateInstructionError] or the created library row.
     */
    suspend fun createInstruction(
        userId: Long,
        request: CreateInstructionRequest
    ): Either<CreateInstructionError, AgentInstructionDto>

    /**
     * Replaces the content of the instruction row [UpdateInstructionRequest.id] names, provided
     * [userId] owns it.
     *
     * Because the row is shared, the write reaches every agent role that links it; the reported row
     * lists those roles so the caller can say who is affected.
     *
     * @param userId User whose ownership of the row is required.
     * @param request The new content, carrying the id of the row to rewrite.
     * @return Either an [UpdateInstructionError] or the updated library row.
     */
    suspend fun updateInstruction(
        userId: Long,
        request: UpdateInstructionRequest
    ): Either<UpdateInstructionError, AgentInstructionDto>

    /**
     * Deletes the instruction row [instructionId], provided [userId] owns it and no agent role links
     * it any more.
     *
     * The row is shared, so deleting it would drop a link from every role that uses it; the deletion
     * is therefore refused with the linking role ids while at least one link remains, and the caller
     * must unassign the row from every role (or delete those roles) first. Deleting destroys the
     * content, which is the only way to remove a library entry; unlinking the last role leaves the row
     * in place.
     *
     * @param userId User whose ownership of the row is required.
     * @param instructionId The row to delete.
     * @return Either a [DeleteInstructionError], or Unit when the row is gone.
     */
    suspend fun deleteInstruction(userId: Long, instructionId: Long): Either<DeleteInstructionError, Unit>
}
