package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.InstructionRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.InstructionApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [InstructionRepository] backed by [InstructionApi].
 *
 * Maintains an in-memory [StateFlow] cache of the user's instruction library, always stored in
 * name-ascending order (the server returns id-ascending rows, which is a storage artifact; the picker
 * that renders the library is scanned by label), and updates it after every successful write so the
 * role form can offer a freshly created row without an extra reload.
 *
 * @property instructionApi The API client used for all instruction library requests.
 */
class DefaultInstructionRepository(
    private val instructionApi: InstructionApi
) : InstructionRepository {

    companion object {
        private val logger = kmpLogger<DefaultInstructionRepository>()

        /**
         * Sorts the library by its user-facing label ascending.
         *
         * The sort is stable, so rows sharing a label keep the server's id-ascending order and the
         * picker's list stays deterministic.
         *
         * @param instructions The rows to order.
         * @return A new list ordered by [AgentInstructionDto.name].
         */
        private fun sortByName(instructions: List<AgentInstructionDto>): List<AgentInstructionDto> =
            instructions.sortedBy { it.name }
    }

    private val _instructions =
        MutableStateFlow<DataState<RepositoryError, List<AgentInstructionDto>>>(DataState.Idle)
    override val instructions: StateFlow<DataState<RepositoryError, List<AgentInstructionDto>>> =
        _instructions.asStateFlow()

    override suspend fun loadInstructions(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations (mirrors every other repository).
        if (_instructions.value.isLoading) return Unit.right()

        _instructions.update { DataState.Loading }

        return instructionApi.listInstructions().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load instructions")
                logger.warn("Failed to load instructions: ${repoError.message}")
                _instructions.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { library ->
                // The load is the one write that happens while the state is Loading, so it sets the
                // sorted success state directly instead of going through [updateInstructionsState].
                _instructions.update { DataState.Success(sortByName(library)) }
                logger.debug("Successfully loaded ${library.size} instructions")
                Unit.right()
            }
        )
    }

    override suspend fun createInstruction(
        request: CreateInstructionRequest
    ): Either<RepositoryError, AgentInstructionDto> {
        logger.info("Creating instruction of type '${request.type}'")

        return instructionApi.createInstruction(request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to create instruction")
                logger.warn("Failed to create instruction: ${repoError.message}")
                repoError.left()
            },
            ifRight = { created ->
                logger.debug("Created instruction ID: ${created.id}")
                // The new row is linkable immediately, so the picker may offer it right away.
                updateInstructionsState { list -> list + created }
                created.right()
            }
        )
    }

    override suspend fun updateInstruction(
        request: UpdateInstructionRequest
    ): Either<RepositoryError, AgentInstructionDto> {
        logger.info("Updating instruction ID: ${request.id}")

        return instructionApi.updateInstruction(request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update instruction ID: ${request.id}")
                logger.warn("Failed to update instruction ID: ${request.id}: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updated ->
                logger.debug("Updated instruction ID: ${updated.id}")
                updateInstructionsState { list ->
                    if (list.any { it.id == updated.id }) {
                        list.map { if (it.id == updated.id) updated else it }
                    } else {
                        list + updated
                    }
                }
                updated.right()
            }
        )
    }

    override suspend fun deleteInstruction(instructionId: Long): Either<RepositoryError, Unit> {
        logger.info("Deleting instruction ID: $instructionId")

        return instructionApi.deleteInstruction(instructionId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to delete instruction ID: $instructionId")
                logger.warn("Failed to delete instruction ID: $instructionId: ${repoError.message}")
                repoError.left()
            },
            ifRight = {
                logger.debug("Deleted instruction ID: $instructionId")
                // The row is gone, so it must not stay offerable as a link target or as an unassigned
                // library entry.
                updateInstructionsState { list -> list.filterNot { it.id == instructionId } }
                Unit.right()
            }
        )
    }

    /**
     * Applies [transform] to the cached library, keeping the name-ascending invariant.
     *
     * A mutation is only applied when the cache already holds data (success or idle); a `Loading`
     * state is left alone so [loadInstructions] stays the single owner of the success transition, and
     * an error state is left untouched so a failed stream is not silently turned into a partial list.
     *
     * @param transform Rewrites the current list (append/replace) without ordering concerns.
     */
    private fun updateInstructionsState(transform: (List<AgentInstructionDto>) -> List<AgentInstructionDto>) {
        _instructions.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(sortByName(transform(currentState.data)))
                is DataState.Idle -> DataState.Success(sortByName(transform(emptyList())))
                else -> currentState
            }
        }
    }
}
