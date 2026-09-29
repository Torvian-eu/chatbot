package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between instructions and users.
 *
 * Operates on `instruction_owners` (instruction_id PK, user_id), mirroring the `model_preset_owners`
 * family: an instruction has exactly one owner (instruction_id is the primary key).
 */
interface InstructionOwnershipDao {

    /**
     * Returns the user id owning the given instruction.
     *
     * @param instructionId ID of the instruction.
     * @return Either [GetOwnerError.ResourceNotFound] if no such instruction/owner exists, or the
     *         owner's user id.
     */
    suspend fun getOwner(instructionId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the instruction and a user.
     *
     * @param instructionId ID of the instruction to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(instructionId: Long, userId: Long): Either<SetOwnerError, Unit>
}
