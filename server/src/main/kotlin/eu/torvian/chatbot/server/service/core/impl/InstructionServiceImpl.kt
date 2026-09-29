package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.InstructionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.service.core.InstructionService
import eu.torvian.chatbot.server.service.core.error.instruction.CreateInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.DeleteInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.GetInstructionError
import eu.torvian.chatbot.server.service.core.error.instruction.UpdateInstructionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Implementation of [InstructionService] over the instruction tables.
 *
 * Writes are validated before they are persisted and wrapped in the shared [TransactionScope], so a
 * row and its ownership link appear or disappear together. Ownership checks run through the
 * owner-scoped batch read, which doubles as the existence check. Reported rows carry their linking
 * roles, resolved by one batched reverse-link read per call so no listing path runs a query per row.
 *
 * @property instructionDao DAO for the instruction rows.
 * @property instructionOwnershipDao DAO for `instruction_owners` (the creator owns the row).
 * @property agentRoleInstructionDao DAO for the role links, read to report each row's linking roles.
 * @property transactionScope Transaction wrapper keeping validation and persistence atomic.
 * @property json Codec used to read the stored `custom` JSON text of a row back into the wire shape;
 *           only listing needs it, because a write echoes the authored value instead.
 */
class InstructionServiceImpl(
    private val instructionDao: InstructionDao,
    private val instructionOwnershipDao: InstructionOwnershipDao,
    private val agentRoleInstructionDao: AgentRoleInstructionDao,
    private val transactionScope: TransactionScope,
    private val json: Json
) : InstructionService {

    override suspend fun getAllInstructionsForUser(userId: Long): List<AgentInstructionDto> =
        transactionScope.transaction {
            val rows = instructionDao.getAllInstructionsForUser(userId)
            // One reverse-link read for the whole listing, so a large library never turns into a query
            // per row, and a consumer can narrow the delivered rows without a second request.
            val linkedRoleIdsByInstructionId = linkedRoleIdsFor(rows.map { it.id })
            rows.map { it.toDto(linkedRoleIds = linkedRoleIdsByInstructionId[it.id].orEmpty()) }
        }

    override suspend fun getInstructionById(
        userId: Long,
        instructionId: Long
    ): Either<GetInstructionError, AgentInstructionDto> = transactionScope.transaction {
        either {
            val row = ownedRow(userId, instructionId, GetInstructionError.NotFound(instructionId))
            val linkedRoleIds = linkedRoleIdsFor(listOf(instructionId))[instructionId].orEmpty()
            row.toDto(linkedRoleIds = linkedRoleIds)
        }
    }

    override suspend fun createInstruction(
        userId: Long,
        request: CreateInstructionRequest
    ): Either<CreateInstructionError, AgentInstructionDto> = transactionScope.transaction {
        either {
            InstructionContentRules.validate(
                type = request.type,
                name = request.name,
                message = request.message,
                custom = request.custom,
                raise = this,
                validationFailed = { reason -> CreateInstructionError.ValidationFailed(reason) }
            )
            val created = instructionDao.insertInstruction(
                type = request.type,
                name = request.name,
                message = InstructionContentRules.storedMessage(request.type, request.message),
                custom = request.custom?.toString()
            )
            // A row without an owner would be unmodifiable once created, so an ownership failure must
            // not slip through: it surfaces as its own error and rolls the insert back.
            withError({ error: SetOwnerError ->
                CreateInstructionError.OwnerInsertFailed(error.toString())
            }) {
                instructionOwnershipDao.setOwner(created.id, userId).bind()
            }
            // A brand-new row cannot be linked yet, so its role list is empty by construction.
            created.toDto(custom = request.custom, linkedRoleIds = emptySet())
        }
    }

    override suspend fun updateInstruction(
        userId: Long,
        request: UpdateInstructionRequest
    ): Either<UpdateInstructionError, AgentInstructionDto> = transactionScope.transaction {
        either {
            val existing = instructionDao
                .getInstructionsByIdsForUser(userId, listOf(request.id))
                .singleOrNull()
                ?: raise(UpdateInstructionError.NotFound(request.id))
            InstructionContentRules.validate(
                type = request.type,
                name = request.name,
                message = request.message,
                custom = request.custom,
                raise = this,
                validationFailed = { reason -> UpdateInstructionError.ValidationFailed(reason) }
            )
            // The row is shared, so its kind and model target are part of every linking role's list
            // validity. The endpoint is not role-scoped, so it judges every linking role's resulting
            // list itself and refuses the write only when one of those lists would become invalid.
            val linkedRoleIds = linkedRoleIdsFor(listOf(request.id))[request.id].orEmpty()
            val newRuleInput = AgentRoleInstructionRules.RuleInput(
                type = request.type,
                modelId = request.custom?.modelIdOrNull()
            )
            if (existing.toRuleInput(json) != newRuleInput) {
                validateLinkedRoleInstructionLists(
                    linkedRoleIds = linkedRoleIds.sorted(),
                    instructionId = request.id,
                    newRuleInput = newRuleInput,
                    agentRoleInstructionDao = agentRoleInstructionDao,
                    instructionDao = instructionDao,
                    json = json,
                    onInvalid = { roleIds, reason ->
                        UpdateInstructionError.LinkedRoleInstructionListInvalid(request.id, roleIds, reason)
                    }
                )
            }
            // The row is shared, so this content write reaches every role that links it.
            val rewritten = existing.copy(
                type = request.type,
                name = request.name,
                message = InstructionContentRules.storedMessage(request.type, request.message),
                custom = request.custom?.toString()
            )
            withError({ _: InstructionError -> UpdateInstructionError.NotFound(request.id) }) {
                instructionDao.updateInstruction(rewritten).bind()
            }
            rewritten.toDto(custom = request.custom, linkedRoleIds = linkedRoleIds)
        }
    }

    override suspend fun deleteInstruction(
        userId: Long,
        instructionId: Long
    ): Either<DeleteInstructionError, Unit> = transactionScope.transaction {
        either {
            // Ownership is checked before the links so a foreign or missing row still collapses into
            // the same not-found and the role ids of somebody else's row never leak.
            ownedRow(userId, instructionId, DeleteInstructionError.NotFound(instructionId))
            // A linked row is refused instead of silently unlinking every role. The read and the
            // delete share this transaction, so no link can land between them.
            val linkedRoleIds = linkedRoleIdsFor(listOf(instructionId))[instructionId].orEmpty()
            if (linkedRoleIds.isNotEmpty()) {
                raise(DeleteInstructionError.LinkedToRoles(instructionId, linkedRoleIds.sorted()))
            }
            // Storage refuses to drop a row a role links, so a constraint failure here means a link
            // appeared despite the check: it is reported as the same refusal, naming the roles this
            // transaction can see, and anything else is a row that vanished.
            withError({ error: InstructionError ->
                when (error) {
                    is InstructionError.NotFound -> DeleteInstructionError.NotFound(instructionId)

                    is InstructionError.ForeignKeyViolation -> DeleteInstructionError.LinkedToRoles(
                        instructionId,
                        linkedRoleIdsFor(listOf(instructionId))[instructionId].orEmpty().sorted()
                    )
                }
            }) {
                instructionDao.deleteInstruction(instructionId).bind()
            }
        }
    }

    /**
     * Loads one instruction row owned by the caller.
     *
     * @param userId The requesting user, whose ownership of the row is required.
     * @param instructionId The row to load.
     * @param notFoundError The caller's error to raise when the row is missing or foreign, so each
     *            error surface reports its own not-found shape.
     * @return The owned row.
     */
    private suspend fun <E> Raise<E>.ownedRow(userId: Long, instructionId: Long, notFoundError: E): InstructionEntity =
        instructionDao.getInstructionsByIdsForUser(userId, listOf(instructionId)).singleOrNull()
            ?: raise(notFoundError)

    /**
     * Resolves the linking roles of the given instruction rows.
     *
     * @param instructionIds Instruction row ids to resolve; an empty list short-circuits without a
     *            query.
     * @return Linking role ids per instruction row; unlinked rows are absent from the map.
     */
    private suspend fun linkedRoleIdsFor(instructionIds: List<Long>): Map<Long, Set<Long>> =
        if (instructionIds.isEmpty()) {
            emptyMap()
        } else {
            agentRoleInstructionDao.getLinkedRoleIdsForInstructions(instructionIds.distinct())
        }

    /**
     * Presents a written row as the wire shape.
     *
     * The authored `custom` value is reported as supplied instead of being re-parsed from the stored
     * text, so the response mirrors the request even for an empty object.
     *
     * @receiver The written instruction row.
     * @param custom The authored type-specific data, or null when the kind carries none.
     * @param linkedRoleIds Ids of the roles linking the row.
     * @return The library view of the row.
     */
    private fun InstructionEntity.toDto(
        custom: JsonObject?,
        linkedRoleIds: Set<Long>
    ): AgentInstructionDto = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        // A generated-message kind stores no text, and the text generated for a role has no meaning
        // away from that role, so a library row reports an empty message instead.
        message = message.orEmpty(),
        custom = custom,
        linkedRoleIds = linkedRoleIds
    )

    /**
     * Presents a stored row as the wire shape.
     *
     * Listing has no authored value to echo, so the type-specific data comes from the stored `custom`
     * text; a malformed value degrades to null like every other read of that column.
     *
     * @receiver The stored instruction row.
     * @param linkedRoleIds Ids of the roles linking the row.
     * @return The library view of the row.
     */
    private fun InstructionEntity.toDto(linkedRoleIds: Set<Long>): AgentInstructionDto =
        toDto(custom = parseStoredCustom(json, this.custom), linkedRoleIds = linkedRoleIds)
}
