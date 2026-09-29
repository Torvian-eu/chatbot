package eu.torvian.chatbot.server.data.dao

/**
 * Data Access Object for the ordered agent-role ↔ instruction link table.
 *
 * One instruction row can serve many roles; per-role ordering lives in the link rows' `sequence`
 * (zero-based, contiguous after every write). Link uniqueness (the same row at most once per role)
 * is enforced by the composite primary key, and every write path checks it first to raise a typed
 * logical error before reaching storage. Deleting a role cascades only that role's link rows — never
 * the instruction content or another role's links — while deleting an instruction row is refused by
 * the link foreign key until its last link is gone.
 */
interface AgentRoleInstructionDao {

    /**
     * A single role↔instruction link as stored: the linked row id and its position in the role's
     * ordered list.
     *
     * @property instructionId The linked instruction row id.
     * @property sequence Zero-based position of the instruction within the role's list.
     */
    data class InstructionRef(
        val instructionId: Long,
        val sequence: Int
    )

    /**
     * Loads the ordered instruction links of the given roles.
     *
     * Batch read for role list/detail paths: one query covers every role, and each role's refs come
     * back sorted by `sequence` so the caller can rebuild the ordered instruction list without
     * sorting.
     *
     * @param roleIds Role ids to resolve.
     * @return Per-role ordered link refs; roles without links are absent from the map.
     */
    suspend fun getLinksForRoles(roleIds: List<Long>): Map<Long, List<InstructionRef>>

    /**
     * Resolves which roles link each of the given instructions.
     *
     * Single reverse-lookup batch behind every `linkedRoleIds` computation: one query serves whole
     * role lists and instruction lists alike. Only a row's owner can create links (write paths
     * enforce row and role ownership), so the linking roles are always the owner's roles.
     *
     * @param instructionIds Instruction ids to resolve.
     * @return Per-instruction linking role ids, unordered and duplicate-free (iteration order is
     *         ascending for stable payloads); unlinked instructions are absent from the map.
     */
    suspend fun getLinkedRoleIdsForInstructions(instructionIds: List<Long>): Map<Long, Set<Long>>

    /**
     * Replaces one role's whole instruction link list with the given ordered ids.
     *
     * Full-replacement semantics (delete + insert) with `sequence` = list index, so the stored
     * positions stay contiguous zero-based. Dropped ids lose only this role's link; the instruction
     * rows persist as library entries.
     *
     * @param roleId The role whose links are rewritten.
     * @param orderedInstructionIds The instruction ids in their new order; the same id must not
     *            appear twice (the composite PK rejects duplicates at the storage level).
     */
    suspend fun replaceInstructionsForRole(roleId: Long, orderedInstructionIds: List<Long>)

    /**
     * Links one instruction to a role as its last element (`sequence` = current maximum + 1).
     *
     * Used by the assign flow (append-last semantics); an already-linked pair is rejected by the
     * composite PK at the storage level after the caller's typed check.
     *
     * @param roleId The role to link to.
     * @param instructionId The instruction to link.
     */
    suspend fun appendInstructionForRole(roleId: Long, instructionId: Long)

    /**
     * Removes one role↔instruction link without touching the instruction row.
     *
     * Used by the unassign flow; the surviving links keep their `sequence` values (relative order
     * unchanged) and are re-normalized on the next role write.
     *
     * @param roleId The role to unlink from.
     * @param instructionId The instruction to unlink.
     */
    suspend fun removeInstructionFromRole(roleId: Long, instructionId: Long)
}
