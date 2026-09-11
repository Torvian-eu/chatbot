package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleOwnershipDao
import eu.torvian.chatbot.server.data.dao.AgentRoleToolDao
import eu.torvian.chatbot.server.data.dao.AgentRoleSpawnableRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleDisabledDao
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.SessionDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.AgentRoleError as AgentRoleDaoError
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [AgentRoleService] providing user-scoped agent-role CRUD operations.
 *
 * Uses Arrow's `either { }`/`ensure`/`withError` pattern for typed logical errors and wraps all
 * operations in the shared [TransactionScope]. Instructions are persisted as a raw JSON string
 * (`instructions_json`) so serialization stays at the [AgentRoleMapper] service boundary: the stored
 * shape equals the wire shape (polymorphic instruction DTO list), and the server domain instruction
 * hierarchy is reconstructed per read. The role's tool set is stored in the normalized `agent_role_tools` join
 * table through [agentRoleToolDao] (full replacement on create/update, cascade-deleted with the role
 * or a tool definition). Shared request invariants live in [AgentRoleRequestValidator].
 *
 * @property agentRoleDao DAO for the `agent_roles` table.
 * @property agentRoleToolDao DAO for the `agent_role_tools` join table (the role's tool ids).
 * @property agentRoleSpawnableRoleDao DAO for the role-to-role spawn allow-list.
 * @property agentRoleOwnershipDao DAO for the `agent_role_owners` table (per-user ownership).
 * @property agentRoleDisabledDao DAO for the `agent_role_disabled` side table (per-user disabled state).
 * @property modelPresetDao DAO used to resolve the model presets a role references — the single read
 *            for single-role paths and one batch read for the list path — and to validate an attached
 *            preset.
 * @property settingsDao DAO used to validate an attached preset's settings reference (chat-capability
 *            and agreement with the preset's model).
 * @property toolDefinitionDao DAO used to validate tool references.
 * @property projectDao DAO used to validate project references (ownership and existence).
 * @property sessionDao DAO used to restore the Session Legality Invariant when a role update changes
 *            its project membership (role-update legality sweep).
 * @property json Shared JSON codec used to (de)serialize the `instructions_json` column.
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
        projectDao = projectDao
    )

    /**
     * Row↔domain and domain↔wire mapping plus the instruction codec, constructed from the DAOs
     * and JSON codec this service already receives so the constructor stays unchanged.
     */
    private val mapper = AgentRoleMapper(
        agentRoleDao = agentRoleDao,
        toolDefinitionDao = toolDefinitionDao,
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
        // whole list, and the derived model/settings ids come from that single map.
        val roleIds = entities.map { it.id }
        val toolsByRole = agentRoleToolDao.getToolsForRoles(roleIds)
        val spawnableByRole = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(roleIds)
        val disabledRoleIds = agentRoleDisabledDao.getDisabledRoleIds(userId, roleIds)
        val presetsById = modelPresetDao
            .getPresetsByIdsForUser(userId, entities.mapNotNull { it.modelPresetId }.distinct())
            .associateBy { it.id }
        entities.map {
            mapper.toDto(
                entity = it,
                tools = toolsByRole[it.id].orEmpty(),
                spawnableRoleIds = spawnableByRole[it.id].orEmpty(),
                projectId = it.projectId,
                preset = it.modelPresetId?.let(presetsById::get),
                ownerId = userId,
                disabled = it.id in disabledRoleIds
            )
        }
    }

    override suspend fun getRoleById(userId: Long, roleId: Long): Either<AgentRoleError.NotFound, AgentRoleDto> =
        transactionScope.transaction {
            either {
                val entity = loadOwnedRole(userId, roleId, AgentRoleError.NotFound(roleId))
                val disabled = agentRoleDisabledDao.isRoleDisabled(userId, entity.id)
                val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
                mapper.toDto(
                    entity = entity,
                    tools = agentRoleToolDao.getToolsForRole(entity.id),
                    spawnableRoleIds = spawnableRoleIds,
                    projectId = entity.projectId,
                    preset = resolvePreset(entity),
                    ownerId = userId,
                    disabled = disabled
                )
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
                val disabled = agentRoleDisabledDao.isRoleDisabled(userId, entity.id)
                val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
                mapper.toDto(
                    entity = entity,
                    tools = agentRoleToolDao.getToolsForRole(entity.id),
                    spawnableRoleIds = spawnableRoleIds,
                    projectId = entity.projectId,
                    preset = resolvePreset(entity),
                    ownerId = userId,
                    disabled = disabled
                )
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

    override suspend fun setRoleDisabled(userId: Long, roleId: Long, disabled: Boolean): Either<AgentRoleError.NotFound, AgentRoleDto> =
        transactionScope.transaction {
            either {
                logger.info("Setting disabled=$disabled for agent role $roleId (user $userId)")
                val entity = loadOwnedRole(userId, roleId, AgentRoleError.NotFound(roleId))
                // Idempotent insert/delete of the (user, role) row inside the same transaction as the
                // ownership check; a foreign or nonexistent role is rejected before any write happens.
                agentRoleDisabledDao.setRoleDisabled(userId, entity.id, disabled)
                val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
                mapper.toDto(
                    entity = entity,
                    tools = agentRoleToolDao.getToolsForRole(entity.id),
                    spawnableRoleIds = spawnableRoleIds,
                    projectId = entity.projectId,
                    preset = resolvePreset(entity),
                    ownerId = userId,
                    // The DTO must echo the requested state even if the row pre-existed: the write is
                    // idempotent, so the new value equals the requested value by construction.
                    disabled = disabled
                )
            }
        }

    override suspend fun createRole(
        userId: Long,
        request: CreateAgentRoleRequest
    ): Either<CreateAgentRoleError, AgentRoleDto> = transactionScope.transaction {
        either {
            logger.info("Creating agent role '${request.name}' for user $userId")

            // The attached preset (if any) is resolved and validated here; it is reused below to build
            // the echoed DTO, so the derived model/settings ids need no second read.
            val preset = requestValidator.validateCreate(userId, request).bind()

            // Names are unique per (user, project scope), not globally or per user alone: the new
            // role's project scope (null = unassociated scope) must not equal any other same-name
            // role's scope — the same project id, or both unassociated.
            val sameNameScopes = agentRoleDao.getRoleNameScopesForUser(userId, request.name)
            ensure(sameNameScopes.none { scopesConflict(request.projectId, it.projectId) }) {
                CreateAgentRoleError.NameAlreadyExists(request.name)
            }

            val instructionsJson = mapper.encodeInstructions(request.instructions)

            val entity = agentRoleDao.insertRole(
                name = request.name,
                displayName = request.displayName,
                description = request.description,
                modelPresetId = request.modelPresetId,
                instructionsJson = instructionsJson,
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

            logger.info("Created agent role '${request.name}' (id ${entity.id}) for user $userId")
            mapper.toDto(
                entity = entity,
                tools = request.toolIds,
                spawnableRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                preset = preset,
                ownerId = userId,
                // No side-table row is ever inserted on create: a fresh role is enabled for its owner.
                disabled = false
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

            val preset = requestValidator.validateUpdate(userId, roleId, request).bind()

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
                instructionsJson = mapper.encodeInstructions(request.instructions),
                projectId = request.projectId
            )

            withError({ _: AgentRoleDaoError.NotFound -> UpdateAgentRoleError.NotFound(roleId) }) {
                agentRoleDao.updateRole(updated).bind()
            }

            // Full-replacement semantics preserved: the tool set is rewritten atomically with the role
            // row (delete + insert) inside the same transaction.
            agentRoleToolDao.replaceToolsForRole(roleId, request.toolIds)
            agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(roleId, request.spawnableAgentRoleIds)
            // The project membership is written together with the row (a full replacement via the
            // `project_id` column on the role row).

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
            mapper.toDto(
                entity = updated,
                tools = request.toolIds,
                spawnableRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                preset = preset,
                ownerId = userId,
                disabled = agentRoleDisabledDao.isRoleDisabled(userId, roleId)
            )
        }
    }

    override suspend fun deleteRole(userId: Long, roleId: Long): Either<DeleteAgentRoleError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Deleting agent role $roleId for user $userId")

                // The DAO's delete is id-keyed only, so ownership is checked here (the NotFound
                // collapse hides the existence of foreign roles).
                ensureOwnedBy(userId, roleId, DeleteAgentRoleError.NotFound(roleId))

                withError({ _: AgentRoleDaoError.NotFound -> DeleteAgentRoleError.NotFound(roleId) }) {
                    agentRoleDao.deleteRole(roleId).bind()
                }

                logger.info("Deleted agent role $roleId for user $userId")
            }
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
        return mapper.toDomain(
            entity = entity,
            tools = agentRoleToolDao.getToolsForRole(entity.id),
            spawnableRoleIds = spawnableRoleIds,
            projectId = entity.projectId,
            preset = resolvePreset(entity),
            ownerId = ownerId,
            disabled = agentRoleDisabledDao.isRoleDisabled(userId, entity.id)
        )
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
