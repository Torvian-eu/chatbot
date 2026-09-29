package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for the current user's instruction library.
 *
 * Instructions are shared content: many agent roles link one row by id, so editing it reaches every
 * linked role at the next read. Reusable rows are picked from [instructions] when a role form is
 * filled in, and the flow is the single source of truth for that catalog.
 *
 * Follows the same shape as the other repositories in this layer: a reactive [StateFlow] kept in sync
 * after every successful write, name-ascending (the display order the picker needs).
 */
interface InstructionRepository {

    /**
     * Reactive stream of every instruction owned by the current user.
     *
     * Includes rows no agent role links any more (dropping the last link keeps the row in the
     * library), so the picker offers the whole library rather than only what roles already use.
     *
     * @return StateFlow containing the current library wrapped in [DataState].
     */
    val instructions: StateFlow<DataState<RepositoryError, List<AgentInstructionDto>>>

    /**
     * Loads the user's instruction library from the server and updates [instructions].
     *
     * The whole library is loaded in one request, every row naming the roles that link it: a view that
     * needs a selection (one role's instructions, or the unassigned ones) narrows the delivered stream
     * itself, so changing the selection costs no round trip.
     *
     * A load already in progress makes this a no-op, so concurrent callers never start duplicate
     * requests.
     *
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun loadInstructions(): Either<RepositoryError, Unit>

    /**
     * Creates an instruction owned by the current user and appends it to [instructions].
     *
     * @param request The authored content.
     * @return [Either.Right] with the created [AgentInstructionDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun createInstruction(request: CreateInstructionRequest): Either<RepositoryError, AgentInstructionDto>

    /**
     * Replaces the content of an existing instruction and refreshes its entry in [instructions].
     *
     * Because the row is shared, the change reaches every agent role that links it.
     *
     * @param request The replacement content, carrying the id of the row to rewrite.
     * @return [Either.Right] with the updated [AgentInstructionDto] on success, or [Either.Left] with
     *         [RepositoryError] on failure.
     */
    suspend fun updateInstruction(request: UpdateInstructionRequest): Either<RepositoryError, AgentInstructionDto>

    /**
     * Deletes an instruction and removes it from [instructions].
     *
     * The server refuses a row that an agent role still links, so only an unassigned row can be
     * deleted here; a still-linked row fails with a `resource-in-use` error. The row disappears from
     * the library of every view reading [instructions].
     *
     * @param instructionId The unique identifier of the instruction to delete.
     * @return [Either.Right] with [Unit] on success, or [Either.Left] with [RepositoryError] on failure.
     */
    suspend fun deleteInstruction(instructionId: Long): Either<RepositoryError, Unit>
}
