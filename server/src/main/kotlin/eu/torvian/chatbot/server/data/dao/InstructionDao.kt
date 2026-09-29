package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import eu.torvian.chatbot.server.data.entities.InstructionEntity

/**
 * Data Access Object for shareable instruction entities.
 *
 * Instruction-name uniqueness is deliberately NOT enforced: names are display labels and many rows
 * (including content-level duplicates) may share one. Ownership lives in the separate
 * `instruction_owners` table and is managed through [InstructionOwnershipDao]; the owner-scoped
 * batch read doubles as the ownership check used by every write path. The role links are stored
 * separately in `agent_role_instructions` and managed through [AgentRoleInstructionDao].
 */
interface InstructionDao {

    /**
     * Loads the requested instructions without any ownership scoping, preserving the order of [ids].
     *
     * Plain batch read for paths that resolved ownership earlier (e.g. role reads, whose roles are
     * already user-owned).
     *
     * @param ids Instruction ids to resolve; duplicates and missing ids yield at most one row each.
     * @return The found instruction entities in requested order.
     */
    suspend fun getInstructionsByIds(ids: List<Long>): List<InstructionEntity>

    /**
     * Loads the requested instructions that are owned by [userId], preserving the order of [ids].
     * Missing or foreign instructions are omitted so callers can use the result as an ownership check.
     *
     * @param userId User whose ownership is required.
     * @param ids Instruction ids to resolve.
     * @return Owned instruction entities in requested order.
     */
    suspend fun getInstructionsByIdsForUser(userId: Long, ids: List<Long>): List<InstructionEntity>

    /**
     * Retrieves all instructions owned by the given user, joined through the ownership table.
     *
     * Ordered by `id` ascending so listings are deterministic. Includes unassigned rows (rows with
     * no remaining role link are kept as library entries).
     *
     * @param userId ID of the owner user.
     * @return List of [InstructionEntity] owned by the user; empty list if the user owns none.
     */
    suspend fun getAllInstructionsForUser(userId: Long): List<InstructionEntity>

    /**
     * Retrieves an instruction by its unique ID.
     *
     * The caller is responsible for ownership verification (see [getInstructionsByIdsForUser]); this
     * read is a plain existence check.
     *
     * @param id The unique identifier of the instruction.
     * @return Either [InstructionError.NotFound] if not found, or the [InstructionEntity].
     */
    suspend fun getInstructionById(id: Long): Either<InstructionError.NotFound, InstructionEntity>

    /**
     * Creates a new instruction row.
     *
     * `created_at` and `updated_at` are both set to the current time by the DAO. Technical
     * persistence failures propagate as exceptions. The caller must insert the ownership link via
     * [InstructionOwnershipDao.setOwner] (atomically, in the same transaction).
     *
     * @param type The instruction kind key.
     * @param name Human-readable label of the instruction.
     * @param message Stored instruction text; null marks a generated-message kind whose text is
     *            resolved per linked role at read time.
     * @param custom Raw JSON text of type-specific fields, or null.
     * @return The newly created [InstructionEntity].
     */
    suspend fun insertInstruction(
        type: String,
        name: String,
        message: String?,
        custom: String?
    ): InstructionEntity

    /**
     * Updates an existing instruction row's content (a full replacement of every writable column).
     *
     * `created_at` is preserved; `updated_at` is set to the current time by the DAO. Content changes
     * reach every linked role at the next read because the links reference this single row.
     *
     * @param instruction The [InstructionEntity] with updated values. The ID must match an existing
     *            instruction.
     * @return Either [InstructionError.NotFound] if the instruction does not exist, or the updated
     *         entity.
     */
    suspend fun updateInstruction(
        instruction: InstructionEntity
    ): Either<InstructionError.NotFound, InstructionEntity>

    /**
     * Deletes an instruction row by ID.
     *
     * The link foreign key is `RESTRICT`, so a raw delete of a row some role still links is rejected
     * by the database and reported as [InstructionError.ForeignKeyViolation]: callers unlink the row
     * first. The `instruction_owners` row cascades with the row.
     *
     * @param id The unique identifier of the instruction to delete.
     * @return Either an [InstructionError], or Unit on success.
     */
    suspend fun deleteInstruction(id: Long): Either<InstructionError, Unit>
}
