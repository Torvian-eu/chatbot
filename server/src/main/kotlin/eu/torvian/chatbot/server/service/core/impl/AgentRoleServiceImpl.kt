package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.AgentRoleInstructionDao.InstructionRef
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.InstructionError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.agent.DeleteAgentRoleResult
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.AssignInstructionError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UnassignInstructionError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import eu.torvian.chatbot.server.data.dao.error.AgentRoleError as AgentRoleDaoError

/**
 * Implementation of [AgentRoleService] providing user-scoped agent-role CRUD operations.
 *
 * Uses Arrow's `either { }`/`ensure`/`withError` pattern for typed logical errors and wraps all
 * operations in the shared [TransactionScope]. A role write materializes its `instructionSpecs` —
 * referencing a stored row, creating one inline, or replacing one — plus their ordered links and the
 * role row in that one transaction, so a failed save persists none of them. Deleting a role sweeps
 * its instruction rows in that same transaction and reports each linked row's fate: rows left with
 * no link through the deletion are removed, while rows still linked elsewhere and never-linked rows
 * survive. Every read maps the
 * ordered rows through the single [AgentRoleMapper] funnel. The role's tool set is stored in the
 * normalized `agent_role_tools` join table through [agentRoleToolDao] (full replacement on
 * create/update, cascade-deleted with the role or a tool definition). Shared request invariants live
 * in [AgentRoleRequestValidator].
 *
 * @property agentRoleDao DAO for the `agent_roles` table.
 * @property agentRoleToolDao DAO for the `agent_role_tools` join table (the role's tool ids).
 * @property agentRoleSpawnableRoleDao DAO for the role-to-role spawn allow-list.
 * @property agentRoleOwnershipDao DAO for the `agent_role_owners` table (per-user ownership).
 * @property agentRoleDisabledDao DAO for the `agent_role_disabled` side table (per-user disabled state).
 * @property instructionDao DAO for the instruction rows the role links: ownership checks of the
 *            referenced ids and content writes for inline create/update specs.
 * @property instructionOwnershipDao DAO for `instruction_owners` (an inline-created row's creator
 *            becomes its owner).
 * @property agentRoleInstructionDao DAO for the ordered role↔instruction links.
 * @property modelPresetDao DAO used to resolve the model presets a role references — the single read
 *            for single-role paths and one batch read for the list path — and to validate an attached
 *            preset.
 * @property settingsDao DAO used to validate an attached preset's settings reference (chat-capability
 *            and agreement with the preset's model).
 * @property toolDefinitionDao DAO used to validate tool references.
 * @property projectDao DAO used to validate project references (ownership and existence).
 * @property sessionDao DAO used to restore the Session Legality Invariant when a role update changes
 *            its project membership (role-update legality sweep).
 * @property json Shared JSON codec used to parse instruction `custom` JSON text.
 * @property transactionScope Transaction wrapper that keeps validation + persistence atomic.
 *
 * Project membership is a single nullable `project_id` column on the role row (a role belongs to at
 * most one project), so the membership is written together with the row in `insertRole`/`updateRole`
 * and read from the loaded entity — no separate membership DAO is needed for roles.
 *
 * The role's LLM configuration lives **only** in the referenced model preset
 * (`agent_roles.model_preset_id`). This service therefore resolves the preset
 * ([ModelPresetDao.getPresetById] for single-role reads, [ModelPresetDao.getPresetsByIdsForUser] once
 * for a whole list) and derives the domain role's `modelId`/`modelSettingsId` from it inside the
 * single [AgentRoleMapper] funnel, so every read path reports the same values without persisting
 * them. There is deliberately no `ModelDao` dependency: the model reference needs no separate lookup
 * because a preset's non-null references are guaranteed to exist by their foreign keys, and the
 * model↔settings agreement is checked against the loaded settings row.
 */
class AgentRoleServiceImpl(
    private val agentRoleDao: AgentRoleDao,
    private val agentRoleToolDao: AgentRoleToolDao,
    private val agentRoleOwnershipDao: AgentRoleOwnershipDao,
    private val agentRoleDisabledDao: AgentRoleDisabledDao,
    private val instructionDao: InstructionDao,
    private val instructionOwnershipDao: InstructionOwnershipDao,
    private val agentRoleInstructionDao: AgentRoleInstructionDao,
    private val modelPresetDao: ModelPresetDao,
    private val settingsDao: SettingsDao,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val json: Json,
    private val transactionScope: TransactionScope,
    private val agentRoleSpawnableRoleDao: AgentRoleSpawnableRoleDao,
    private val projectDao: ProjectDao,
    private val sessionDao: SessionDao
) : AgentRoleService {

    /**
     * Shared request validation (name shape, attached preset, tools, spawn targets, project,
     * instructions), constructed from the DAOs this service already receives so the constructor
     * and the Koin wiring stay unchanged.
     */
    private val requestValidator = AgentRoleRequestValidator(
        agentRoleDao = agentRoleDao,
        modelPresetDao = modelPresetDao,
        settingsDao = settingsDao,
        toolDefinitionDao = toolDefinitionDao,
        projectDao = projectDao,
        instructionDao = instructionDao,
        json = json
    )

    /**
     * Row↔domain and domain↔wire mapping plus the spawn advertisement loader, constructed from the
     * DAOs and JSON codec this service already receives so the constructor stays unchanged.
     */
    private val mapper = AgentRoleMapper(
        agentRoleDao = agentRoleDao,
        toolDefinitionDao = toolDefinitionDao,
        projectDao = projectDao,
        json = json
    )

    companion object {
        /** Logger used for service-level diagnostics. */
        private val logger: Logger = LogManager.getLogger(AgentRoleServiceImpl::class.java)
    }

    override suspend fun getAllRolesForUser(userId: Long): List<AgentRoleDto> = transactionScope.transaction {
        logger.debug("Retrieving agent roles for user $userId")
        val entities = agentRoleDao.getAllRolesForUser(userId)
        // Batch-load every role's tool ids, spawn allow-list ids, project ids and the user's disabled
        // ids in one query each so the list endpoint avoids an N+1 read (mirrors the spawn allow-list
        // batch pattern). The referenced model presets are batch-loaded the same way: one query for the
        // whole list, and the derived model/settings ids come from that single map. Instructions add
        // three batched queries (links per role, content rows, reverse links per row) regardless of
        // the number of roles.
        val roleIds = entities.map { it.id }
        val toolsByRole = agentRoleToolDao.getToolsForRoles(roleIds)
        val spawnableByRole = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(roleIds)
        val disabledRoleIds = agentRoleDisabledDao.getDisabledRoleIds(userId, roleIds)
        val presetsById = modelPresetDao
            .getPresetsByIdsForUser(userId, entities.mapNotNull { it.modelPresetId }.distinct())
            .associateBy { it.id }
        val (instructionsByRole, linkedRoleIdsByInstructionId) = loadInstructionsForRoles(roleIds)
        entities.map {
            mapper.toDto(
                entity = it,
                tools = toolsByRole[it.id].orEmpty(),
                spawnableRoleIds = spawnableByRole[it.id].orEmpty(),
                projectId = it.projectId,
                preset = it.modelPresetId?.let(presetsById::get),
                ownerId = userId,
                disabled = it.id in disabledRoleIds,
                instructions = instructionsByRole[it.id].orEmpty(),
                linkedRoleIdsByInstructionId = linkedRoleIdsByInstructionId
            )
        }
    }

    override suspend fun getRoleById(userId: Long, roleId: Long): Either<AgentRoleError.NotFound, AgentRoleDto> =
        transactionScope.transaction {
            either {
                val entity = loadOwnedRole(userId, roleId, AgentRoleError.NotFound(roleId))
                loadPersistedRoleDto(userId, entity, agentRoleDisabledDao.isRoleDisabled(userId, entity.id))
            }
        }

    override suspend fun getRoleByName(
        userId: Long,
        name: String,
        projectId: Long?
    ): Either<AgentRoleError.NotFoundByName, AgentRoleDto> =
        transactionScope.transaction {
            either {
                // The lookup is user-scoped and project-scope-parameterized (names are only unique per
                // user and project scope), so no separate ownership verification is needed. `null`
                // resolves the unassociated scope; a project id resolves membership in that project.
                val entity = withError({ _: AgentRoleDaoError.NotFoundByName -> AgentRoleError.NotFoundByName(name) }) {
                    agentRoleDao.getRoleByNameForUser(userId, name, projectId).bind()
                }
                loadPersistedRoleDto(userId, entity, agentRoleDisabledDao.isRoleDisabled(userId, entity.id))
            }
        }

    override suspend fun getAgentRoleById(userId: Long, roleId: Long): Either<AgentRoleError.NotFound, AgentRole> =
        transactionScope.transaction {
            either {
                val entity = withError({ _: AgentRoleDaoError.NotFound -> AgentRoleError.NotFound(roleId) }) {
                    agentRoleDao.getRoleById(roleId).bind()
                }
                // A role row without an ownership row is a database inconsistency: the owner id scopes
                // the dynamic instruction loaders (target-summary queries), and a 0 fallback would
                // silently produce empty spawn allow-list prompts. Report it as not-found and log it.
                val ownerId = withError({ ownerError: GetOwnerError ->
                    logger.error(
                        "Agent role $roleId exists but has no ownership row " +
                                "(database inconsistency): $ownerError"
                    )
                    AgentRoleError.NotFound(roleId)
                }) {
                    agentRoleOwnershipDao.getOwner(roleId).bind()
                }
                loadDomainRole(entity, ownerId, userId)
            }
        }

    override suspend fun setRoleDisabled(
        userId: Long,
        roleId: Long,
        disabled: Boolean
    ): Either<AgentRoleError.NotFound, AgentRoleDto> =
        transactionScope.transaction {
            either {
                logger.info("Setting disabled=$disabled for agent role $roleId (user $userId)")
                val entity = loadOwnedRole(userId, roleId, AgentRoleError.NotFound(roleId))
                // Idempotent insert/delete of the (user, role) row inside the same transaction as the
                // ownership check; a foreign or nonexistent role is rejected before any write happens.
                agentRoleDisabledDao.setRoleDisabled(userId, entity.id, disabled)
                // The DTO must echo the requested state even if the row pre-existed: the write is
                // idempotent, so the new value equals the requested value by construction.
                loadPersistedRoleDto(userId, entity, disabled)
            }
        }

    override suspend fun createRole(
        userId: Long,
        request: CreateAgentRoleRequest
    ): Either<CreateAgentRoleError, AgentRoleDto> = transactionScope.transaction {
        either {
            logger.info("Creating agent role '${request.name}' for user $userId")

            // The attached preset (if any) and the instruction specs are resolved and validated here;
            // the preset is reused below to build the echoed DTO and the loaded instruction rows feed
            // the materialization, so nothing below needs a second read.
            val validated = requestValidator.validateCreate(userId, request).bind()

            // Names are unique per (user, project scope), not globally or per user alone: the new
            // role's project scope (null = unassociated scope) must not equal any other same-name
            // role's scope — the same project id, or both unassociated.
            val sameNameScopes = agentRoleDao.getRoleNameScopesForUser(userId, request.name)
            ensure(sameNameScopes.none { scopesConflict(request.projectId, it.projectId) }) {
                CreateAgentRoleError.NameAlreadyExists(request.name)
            }

            val entity = agentRoleDao.insertRole(
                name = request.name,
                displayName = request.displayName,
                description = request.description,
                modelPresetId = request.modelPresetId,
                projectId = request.projectId
            )

            // Persist the tool set in the join table (a full replacement of the new role's empty set),
            // atomically with the role row and its ownership inside the same transaction.
            agentRoleToolDao.replaceToolsForRole(entity.id, request.toolIds)
            agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(entity.id, request.spawnableAgentRoleIds)
            // The project membership is written together with the row (no separate membership table).

            withError({ ownershipError: SetOwnerError ->
                CreateAgentRoleError.OwnerInsertFailed(ownershipError.toString())
            }) {
                agentRoleOwnershipDao.setOwner(entity.id, userId).bind()
            }

            // Inline instruction specs materialize their rows and ownership immediately before the
            // link rewrite. Every failure below must propagate as a `Left` out of the outermost
            // transaction block: that is what rolls the whole save back (nested transaction blocks
            // have no rollback point of their own), so a failed save leaves nothing behind.
            val orderedInstructionIds = materializeInstructionSpecs(
                userId = userId,
                roleId = entity.id,
                specs = request.instructionSpecs,
                referencedInstructions = validated.referencedInstructions,
                instructionNotFound = { instructionId -> CreateAgentRoleError.InstructionNotFound(instructionId) },
                linkedRoleListInvalid = { instructionId, linkedRoleIds, reason ->
                    CreateAgentRoleError.LinkedRoleInstructionListInvalid(instructionId, linkedRoleIds, reason)
                },
                ownerInsertFailed = { reason -> CreateAgentRoleError.InstructionOwnerInsertFailed(reason) }
            )

            // Full replacement of the role's instruction links: the materialized ids in the payload's
            // order, and the validator has already confirmed every referenced row exists and is owned.
            agentRoleInstructionDao.replaceInstructionsForRole(entity.id, orderedInstructionIds)

            logger.info("Created agent role '${request.name}' (id ${entity.id}) for user $userId")
            // The echo reads the stored links (one batched query) rather than reusing a write result, so
            // the reported order and the computed linking roles come from persisted state.
            val (instructionsByRole, linkedRoleIdsByInstructionId) = loadInstructionsForRoles(listOf(entity.id))
            mapper.toDto(
                entity = entity,
                tools = request.toolIds,
                spawnableRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                preset = validated.preset,
                ownerId = userId,
                // No side-table row is ever inserted on create: a fresh role is enabled for its owner.
                disabled = false,
                instructions = instructionsByRole[entity.id].orEmpty(),
                linkedRoleIdsByInstructionId = linkedRoleIdsByInstructionId
            )
        }
    }

    override suspend fun updateRole(
        userId: Long,
        roleId: Long,
        request: UpdateAgentRoleRequest
    ): Either<UpdateAgentRoleError, AgentRoleDto> = transactionScope.transaction {
        either {
            logger.info("Updating agent role $roleId for user $userId")

            val existing = loadOwnedRole(userId, roleId, UpdateAgentRoleError.NotFound(roleId))
            // The current membership rides the loaded entity (single project column); it is needed by
            // the scope-sensitive uniqueness check below and to compare against the request's value.
            val currentProjectId = existing.projectId

            val validated = requestValidator.validateUpdate(userId, request).bind()

            // Name uniqueness is scoped per (user, project scope). The check is scope-sensitive: it
            // runs whenever the update changes the name and/or the project. The role being updated is
            // excluded from the same-name set (its new scope is the candidate scope, so it can never
            // conflict with itself).
            if (request.name != existing.name || request.projectId != currentProjectId) {
                val sameNameScopes = agentRoleDao.getRoleNameScopesForUser(userId, request.name)
                ensure(
                    sameNameScopes.none {
                        it.roleId != roleId && scopesConflict(request.projectId, it.projectId)
                    }
                ) {
                    UpdateAgentRoleError.NameAlreadyExists(request.name)
                }
            }

            val updated = existing.copy(
                name = request.name,
                displayName = request.displayName,
                description = request.description,
                modelPresetId = request.modelPresetId,
                projectId = request.projectId
            )

            withError({ _: AgentRoleDaoError.NotFound -> UpdateAgentRoleError.NotFound(roleId) }) {
                agentRoleDao.updateRole(updated).bind()
            }

            // Full-replacement semantics preserved: the tool set and the spawn allow-list are
            // rewritten atomically with the role row inside the same transaction. The project
            // membership is written together with the row (a full replacement via the `project_id`
            // column).
            agentRoleToolDao.replaceToolsForRole(roleId, request.toolIds)
            agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(roleId, request.spawnableAgentRoleIds)

            // Inline instruction specs materialize exactly as in [createRole]: rows and ownership
            // first, then the full-replacement link rewrite — all rolled back together on failure.
            val orderedInstructionIds = materializeInstructionSpecs(
                userId = userId,
                roleId = roleId,
                specs = request.instructionSpecs,
                referencedInstructions = validated.referencedInstructions,
                instructionNotFound = { instructionId -> UpdateAgentRoleError.InstructionNotFound(instructionId) },
                linkedRoleListInvalid = { instructionId, linkedRoleIds, reason ->
                    UpdateAgentRoleError.LinkedRoleInstructionListInvalid(instructionId, linkedRoleIds, reason)
                },
                ownerInsertFailed = { reason -> UpdateAgentRoleError.InstructionOwnerInsertFailed(reason) }
            )

            // Full-replacement semantics preserved: the role's instruction links are rewritten
            // atomically with the role row inside the same transaction, in the payload's order.
            agentRoleInstructionDao.replaceInstructionsForRole(roleId, orderedInstructionIds)

            // Legality sweep: clear the role on every session using it whose pair became illegal under the
            // new membership — a session whose project differs from the role's single project (a
            // project-bound role with a project-less session, or the session's project no longer
            // matching). Same transaction as the membership write.
            val sessionPairs = sessionDao.getSessionProjectPairsForRole(roleId)
            val illegalSessionIds = sessionPairs
                .filter { (_, sessionProjectId) -> sessionProjectId != request.projectId }
                .map { it.sessionId }
            sessionDao.clearAgentRoleForSessions(illegalSessionIds)

            logger.info("Updated agent role $roleId for user $userId")
            // The side-table disabled marker is untouched by the full-replacement row update, so the
            // returned DTO must re-read the per-user flag (single-role existence check, mirrors the
            // single role paths) rather than defaulting it.
            val (instructionsByRole, linkedRoleIdsByInstructionId) = loadInstructionsForRoles(listOf(roleId))
            mapper.toDto(
                entity = updated,
                tools = request.toolIds,
                spawnableRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                preset = validated.preset,
                ownerId = userId,
                disabled = agentRoleDisabledDao.isRoleDisabled(userId, roleId),
                instructions = instructionsByRole[roleId].orEmpty(),
                linkedRoleIdsByInstructionId = linkedRoleIdsByInstructionId
            )
        }
    }

    override suspend fun assignInstruction(
        userId: Long,
        roleId: Long,
        instructionId: Long
    ): Either<AssignInstructionError, AgentRoleDto> = transactionScope.transaction {
        either {
            logger.info("Assigning instruction $instructionId to agent role $roleId for user $userId")

            val entity = loadOwnedRole(userId, roleId, AssignInstructionError.RoleNotFound(roleId))
            // The row must exist and be owned by the caller; the owner-scoped batch read doubles as the
            // ownership check, so a missing and a foreign row collapse into the same error.
            val row = instructionDao
                .getInstructionsByIdsForUser(userId, listOf(instructionId))
                .singleOrNull()
                ?: raise(AssignInstructionError.InstructionNotFound(instructionId))

            val links = agentRoleInstructionDao.getLinksForRoles(listOf(roleId))[roleId].orEmpty()
            ensure(links.none { it.instructionId == instructionId }) {
                AssignInstructionError.AlreadyLinked(instructionId)
            }

            // The rules judge the *resulting* list: the role's current rows in their stored order plus
            // the appended one, so a second singleton kind is rejected before any write.
            AgentRoleInstructionRules.validate(
                instructions = ruleInputsForLinks(links) + row.toRuleInput(json),
                raise = this,
                instructionValidationFailed = { reason ->
                    AssignInstructionError.InstructionValidationFailed(reason)
                }
            )

            // Append-last semantics: the new link goes behind the role's current maximum position.
            agentRoleInstructionDao.appendInstructionForRole(roleId, instructionId)

            logger.info("Assigned instruction $instructionId to agent role $roleId for user $userId")
            // The echo re-reads the stored links so the reported order and the computed linking roles
            // come from persisted state.
            loadPersistedRoleDto(userId, entity, agentRoleDisabledDao.isRoleDisabled(userId, entity.id))
        }
    }

    override suspend fun unassignInstruction(
        userId: Long,
        roleId: Long,
        instructionId: Long
    ): Either<UnassignInstructionError, AgentRoleDto> = transactionScope.transaction {
        either {
            logger.info("Unassigning instruction $instructionId from agent role $roleId for user $userId")

            val entity = loadOwnedRole(userId, roleId, UnassignInstructionError.RoleNotFound(roleId))
            instructionDao
                .getInstructionsByIdsForUser(userId, listOf(instructionId))
                .singleOrNull()
                ?: raise(UnassignInstructionError.InstructionNotFound(instructionId))

            val links = agentRoleInstructionDao.getLinksForRoles(listOf(roleId))[roleId].orEmpty()
            ensure(links.any { it.instructionId == instructionId }) {
                UnassignInstructionError.NotLinked(instructionId)
            }

            // Removing a link leaves a hole in the middle of the sequence, so the surviving links keep
            // their relative order but are re-normalized to a contiguous zero-based list. The row itself
            // is never touched: only its link disappears.
            val remaining = links.sortedBy { it.sequence }.filterNot { it.instructionId == instructionId }
            agentRoleInstructionDao.removeInstructionFromRole(roleId, instructionId)
            val isContiguousAfterRemoval = remaining.withIndex().all { (index, ref) -> ref.sequence == index }
            if (!isContiguousAfterRemoval) {
                agentRoleInstructionDao.replaceInstructionsForRole(roleId, remaining.map { it.instructionId })
            }

            logger.info("Unassigned instruction $instructionId from agent role $roleId for user $userId")
            loadPersistedRoleDto(userId, entity, agentRoleDisabledDao.isRoleDisabled(userId, entity.id))
        }
    }

    override suspend fun deleteRole(userId: Long, roleId: Long): Either<DeleteAgentRoleError, DeleteAgentRoleResult> =
        transactionScope.transaction {
            either {
                logger.info("Deleting agent role $roleId for user $userId")

                // The DAO's delete is id-keyed only, so ownership is checked here (the NotFound
                // collapse hides the existence of foreign roles).
                ensureOwnedBy(userId, roleId, DeleteAgentRoleError.NotFound(roleId))

                // Snapshot before the role row goes: its link rows vanish with it by FK cascade, so
                // afterwards no read can tell which instruction rows this role linked.
                val candidateIds = agentRoleInstructionDao.getLinksForRoles(listOf(roleId))[roleId]
                    .orEmpty()
                    .map { it.instructionId }
                    .distinct()

                withError({ _: AgentRoleDaoError.NotFound -> DeleteAgentRoleError.NotFound(roleId) }) {
                    agentRoleDao.deleteRole(roleId).bind()
                }

                val sweep = sweepLinkedInstructions(candidateIds)

                logger.info("Deleted agent role $roleId for user $userId")
                sweep
            }
        }

    /**
     * Removes the linked instruction rows the deleted role left without any link and reports every
     * candidate's fate.
     *
     * Candidates are only the rows the deleted role linked: each is removed exactly when nothing
     * links it after the role's links cascaded away, and kept when another role still links it.
     * Runs inside the caller's transaction so the sweep commits or rolls back together with the
     * role delete.
     *
     * @param candidateIds Ids the deleted role linked before its link rows were removed.
     * @return The removed and the kept ids, both in candidate order.
     */
    private suspend fun sweepLinkedInstructions(candidateIds: List<Long>): DeleteAgentRoleResult {
        if (candidateIds.isEmpty()) return DeleteAgentRoleResult(emptyList(), emptyList())
        // The count runs after the cascade, so a candidate is doomed exactly when nothing links it now.
        val remainingLinks = agentRoleInstructionDao.getLinkedRoleIdsForInstructions(candidateIds)
        val (doomed, kept) = candidateIds.partition { remainingLinks[it].isNullOrEmpty() }
        val deleted = doomed.filter { instructionId ->
            // The row was linked moments ago and writers serialize, so a failure cannot occur here. A
            // logged no-op must not fail the role deletion over it, because the role is already gone;
            // such a row is reported as deleted only when this call actually deleted it.
            instructionDao.deleteInstruction(instructionId).fold(
                ifLeft = { error ->
                    logger.debug("Instruction {} was not deleted during role-delete cleanup ({})", instructionId, error)
                    false
                },
                ifRight = { true }
            )
        }
        return DeleteAgentRoleResult(deletedInstructionIds = deleted, retainedInstructionIds = kept)
    }

    /**
     * Whether two same-name roles' project scopes are identical (the scope-equality conflict rule).
     *
     * A role occupies exactly one scope: its single project id, or the singleton **unassociated
     * scope** when the id is null. Two scopes conflict exactly when they are equal — the same project
     * id, or both null (both unassociated). Different scopes never conflict, so a name may be reused
     * across projects (and by an unassociated role next to in-project roles).
     *
     * @param projectIdA The first role's project id (null = unassociated).
     * @param projectIdB The second role's project id (null = unassociated).
     * @return `true` if the scopes are identical, `false` otherwise.
     */
    private fun scopesConflict(projectIdA: Long?, projectIdB: Long?): Boolean = projectIdA == projectIdB

    /**
     * Materializes a validated instruction spec list and returns the ordered ids to link.
     *
     * Runs after all validation, inside the caller's transaction: `Create` inserts a row plus its
     * ownership link, `Update` rewrites its target's content in place, and `Link` only contributes its
     * id. An `Update` that changes its target's kind or model target is allowed only when every other
     * role linking that row still ends up with a valid instruction list, because the row's input is
     * part of those roles' list validity and this save only judges the written role's list. Every
     * failure must surface as a raised error so the outermost transaction block rolls the whole save
     * back — a partially materialized spec list must never be committed.
     *
     * @receiver The raise scope of the caller's `either { }` block.
     * @param userId The requesting user, who becomes the owner of every created row.
     * @param roleId The role being written, whose own list this save already validated.
     * @param specs The validated spec list in the role's order.
     * @param referencedInstructions Target rows loaded during validation, keyed by row id.
     * @param instructionNotFound Factory building the caller's error when an `Update` target vanished
     *            between validation and the write.
     * @param linkedRoleListInvalid Factory building the caller's error when an `Update` target's new
     *            input would leave another linking role with an invalid list.
     * @param ownerInsertFailed Factory building the caller's error when a created row's ownership link
     *            cannot be inserted.
     * @return The instruction ids in the role's order.
     */
    private suspend fun <E> Raise<E>.materializeInstructionSpecs(
        userId: Long,
        roleId: Long,
        specs: List<InstructionSlot>,
        referencedInstructions: Map<Long, InstructionEntity>,
        instructionNotFound: (instructionId: Long) -> E,
        linkedRoleListInvalid: (instructionId: Long, linkedRoleIds: List<Long>, reason: String) -> E,
        ownerInsertFailed: (reason: String) -> E
    ): List<Long> = specs.map { slot ->
        when (slot) {
            // A pure reference never rewrites content, so a concurrent edit of the row survives this
            // save.
            is InstructionSlot.Link -> slot.id

            is InstructionSlot.Create -> {
                val created = instructionDao.insertInstruction(
                    type = slot.content.type,
                    name = slot.content.name,
                    message = InstructionContentRules.storedMessage(slot.content.type, slot.content.message),
                    custom = slot.content.custom?.toString()
                )
                // A row without an owner would be unmodifiable once created, so an ownership failure
                // must surface instead of slipping through.
                withError({ error: SetOwnerError -> ownerInsertFailed(error.toString()) }) {
                    instructionOwnershipDao.setOwner(created.id, userId).bind()
                }
                created.id
            }

            is InstructionSlot.Update -> {
                // The rewrite keeps the row's id, so the new content reaches every role linking the
                // row (shared-row semantics). A concurrent delete collapses to not-found.
                val existing = referencedInstructions.getValue(slot.content.id)
                // The row's kind and model target are part of every linking role's list validity, but
                // this save validated only the written role's list, so the other linking roles'
                // resulting lists are judged here: the change is refused only when one would become
                // invalid.
                val newRuleInput = AgentRoleInstructionRules.RuleInput(
                    type = slot.content.type,
                    modelId = slot.content.custom?.modelIdOrNull()
                )
                if (existing.toRuleInput(json) != newRuleInput) {
                    val otherRoleIds = agentRoleInstructionDao
                        .getLinkedRoleIdsForInstructions(listOf(slot.content.id))[slot.content.id]
                        .orEmpty()
                        .filter { it != roleId }
                        .sorted()
                    validateLinkedRoleInstructionLists(
                        linkedRoleIds = otherRoleIds,
                        instructionId = slot.content.id,
                        newRuleInput = newRuleInput,
                        agentRoleInstructionDao = agentRoleInstructionDao,
                        instructionDao = instructionDao,
                        json = json,
                        onInvalid = { roleIds, reason -> linkedRoleListInvalid(slot.content.id, roleIds, reason) }
                    )
                }
                val rewritten = existing.copy(
                    type = slot.content.type,
                    name = slot.content.name,
                    message = InstructionContentRules.storedMessage(slot.content.type, slot.content.message),
                    custom = slot.content.custom?.toString()
                )
                withError({ _: InstructionError -> instructionNotFound(slot.content.id) }) {
                    instructionDao.updateInstruction(rewritten).bind()
                }
                rewritten.id
            }
        }
    }

    // --- Ownership helpers ---

    /**
     * Loads an agent role and verifies that [userId] owns it.
     *
     * Ownership mismatches are reported as the provided [notFoundError] so the service does not leak
     * the existence of roles owned by other users, and the caller's error surface stays uniform.
     *
     * @param userId The requesting user.
     * @param roleId The role to load.
     * @param notFoundError The not-found error to raise when the role is missing or not owned.
     * @return The [AgentRoleEntity] when owned, or a not-found error via the raise scope.
     */
    private suspend fun <E> Raise<E>.loadOwnedRole(userId: Long, roleId: Long, notFoundError: E): AgentRoleEntity {
        val entity = withError({ _: AgentRoleDaoError.NotFound -> notFoundError }) {
            agentRoleDao.getRoleById(roleId).bind()
        }
        ensureOwnedBy(userId, entity.id, notFoundError)
        return entity
    }

    /**
     * Ensures the given user owns the given role.
     *
     * @param userId The requesting user.
     * @param roleId The role to check.
     * @param notFoundError The not-found error to raise on ownership mismatch.
     */
    private suspend fun <E> Raise<E>.ensureOwnedBy(userId: Long, roleId: Long, notFoundError: E) {
        val ownerId = withError({ _: GetOwnerError -> notFoundError }) {
            agentRoleOwnershipDao.getOwner(roleId).bind()
        }
        ensure(ownerId == userId) { notFoundError }
    }

    // --- Read helpers ---

    /**
     * Batch-loads the instruction content of the given roles: the ordered rows per role and the
     * linking-role ids per instruction row.
     *
     * Three batched queries cover any number of roles (link rows per role, content rows by id, and
     * one reverse-link lookup for the shared computation), so no read path becomes N+1. A link whose
     * row is missing (impossible under enforced FKs) is skipped rather than failing the read.
     *
     * @param roleIds The roles whose instructions are loaded.
     * @return Ordered rows per role, plus linking-role ids per instruction row (both keyed by role
     *         or instruction id).
     */
    private suspend fun loadInstructionsForRoles(
        roleIds: List<Long>
    ): Pair<Map<Long, List<InstructionEntity>>, Map<Long, Set<Long>>> {
        val linksByRole = agentRoleInstructionDao.getLinksForRoles(roleIds)
        val instructionIds = linksByRole.values.flatten().map { it.instructionId }.distinct()
        if (instructionIds.isEmpty()) return emptyMap<Long, List<InstructionEntity>>() to emptyMap()
        val rowsById = instructionDao.getInstructionsByIds(instructionIds).associateBy { it.id }
        val linkedRoleIdsByInstructionId = agentRoleInstructionDao.getLinkedRoleIdsForInstructions(instructionIds)
        val instructionsByRole = linksByRole.mapValues { (_, refs) ->
            refs.mapNotNull { rowsById[it.instructionId] }
        }
        return instructionsByRole to linkedRoleIdsByInstructionId
    }

    /**
     * Loads normalized role relations and maps a stored row into its domain representation.
     *
     * @param entity Stored role row.
     * @param ownerId Owner scope used by dynamic instruction loaders.
     * @param userId Requesting user whose per-user disabled state applies (used only for the disabled
     *            flag; the role row itself is resolved by id, and the caller has already bound
     *            [userId] to the session).
     * @return Domain role with current relation ids, preset-derived model/settings ids and lazy
     *         instruction sources.
     */
    private suspend fun loadDomainRole(entity: AgentRoleEntity, ownerId: Long, userId: Long): AgentRole {
        val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
        val (instructionsByRole, linkedRoleIdsByInstructionId) = loadInstructionsForRoles(listOf(entity.id))
        return mapper.toDomain(
            entity = entity,
            tools = agentRoleToolDao.getToolsForRole(entity.id),
            spawnableRoleIds = spawnableRoleIds,
            projectId = entity.projectId,
            preset = resolvePreset(entity),
            ownerId = ownerId,
            disabled = agentRoleDisabledDao.isRoleDisabled(userId, entity.id),
            instructions = instructionsByRole[entity.id].orEmpty(),
            linkedRoleIdsByInstructionId = linkedRoleIdsByInstructionId
        )
    }

    /**
     * Builds the wire response of a persisted role: its relations, preset-derived configuration, the
     * ordered instructions and their recomputed linking-role ids.
     *
     * Every part is read from storage, so a caller that just changed the role's links (or wants the
     * requested value of the idempotent disabled toggle) reports the state after its own write.
     *
     * @param userId The requesting user, who owns the role and whose disabled flag applies.
     * @param entity The stored role row to report.
     * @param disabled The per-user disabled state to report (read back, or the requested value of an
     *            idempotent toggle).
     * @return The role's current state as the wire shape.
     */
    private suspend fun loadPersistedRoleDto(
        userId: Long,
        entity: AgentRoleEntity,
        disabled: Boolean
    ): AgentRoleDto {
        val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
        val (instructionsByRole, linkedRoleIdsByInstructionId) = loadInstructionsForRoles(listOf(entity.id))
        return mapper.toDto(
            entity = entity,
            tools = agentRoleToolDao.getToolsForRole(entity.id),
            spawnableRoleIds = spawnableRoleIds,
            projectId = entity.projectId,
            preset = resolvePreset(entity),
            ownerId = userId,
            disabled = disabled,
            instructions = instructionsByRole[entity.id].orEmpty(),
            linkedRoleIdsByInstructionId = linkedRoleIdsByInstructionId
        )
    }

    /**
     * Projects the rows behind the given links into the per-role rule inputs.
     *
     * A link whose row is missing (impossible under enforced foreign keys) is skipped rather than
     * failing the write; the rules only count kinds, so skipping cannot make an illegal list legal.
     *
     * @param links The role's links in stored order.
     * @return The linked rows as the per-role rules see them.
     */
    private suspend fun ruleInputsForLinks(links: List<InstructionRef>): List<AgentRoleInstructionRules.RuleInput> =
        if (links.isEmpty()) {
            emptyList()
        } else {
            instructionDao.getInstructionsByIds(links.map { it.instructionId }).map { it.toRuleInput(json) }
        }

    /**
     * Resolves the model preset a stored role references, for the single-role read paths (list paths
     * batch-resolve instead).
     *
     * A non-null `model_preset_id` always resolves on a runtime connection because the foreign key is
     * enforced, so a missing row is a database inconsistency: it is logged and reported as "no preset"
     * rather than failing the read. The role then looks preset-less to the caller, while turn
     * preparation still fails loudly with a model-configuration error (it never falls back silently).
     *
     * @param entity The stored role row whose preset reference should be resolved.
     * @return The referenced preset, or null when the role is preset-less or the reference is dangling.
     */
    private suspend fun resolvePreset(entity: AgentRoleEntity): ModelPresetEntity? {
        val presetId = entity.modelPresetId ?: return null
        return modelPresetDao.getPresetById(presetId).fold(
            { error ->
                logger.error(
                    "Agent role ${entity.id} references model preset $presetId, which does not exist " +
                            "(database inconsistency): $error"
                )
                null
            },
            { it }
        )
    }
}
