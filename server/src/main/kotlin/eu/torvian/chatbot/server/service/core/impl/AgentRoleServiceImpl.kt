package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.AgentRoleOwnershipDao
import eu.torvian.chatbot.server.data.dao.AgentRoleToolDao
import eu.torvian.chatbot.server.data.dao.AgentRoleSpawnableRoleDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.error.AgentRoleError as AgentRoleDaoError
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.data.entities.AgentRoleEntity
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.agent.AgentInstruction
import eu.torvian.chatbot.server.service.core.agent.AgentRole
import eu.torvian.chatbot.server.service.core.agent.CustomInstruction
import eu.torvian.chatbot.server.service.core.agent.MainInstruction
import eu.torvian.chatbot.server.service.core.agent.ModelSpecificInstruction
import eu.torvian.chatbot.server.service.core.agent.RoleInstruction
import eu.torvian.chatbot.server.service.core.agent.AgentRoleSummary
import eu.torvian.chatbot.server.service.core.agent.SpawnableAgentsInstruction
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [AgentRoleService] providing user-scoped agent-role CRUD operations.
 *
 * Uses Arrow's `either { }`/`ensure`/`withError` pattern for typed logical errors and wraps all
 * operations in the shared [TransactionScope]. Instructions are persisted as a raw JSON string
 * (`instructions_json`) so serialization stays at this service boundary: the stored shape equals the
 * wire shape (polymorphic [AgentInstructionDto] list), and the server domain [AgentInstruction] hierarchy is
 * reconstructed per read. The role's tool set is stored in the normalized `agent_role_tools` join
 * table through [agentRoleToolDao] (full replacement on create/update, cascade-deleted with the role
 * or a tool definition).
 *
 * @property agentRoleDao DAO for the `agent_roles` table.
 * @property agentRoleToolDao DAO for the `agent_role_tools` join table (the role's tool ids).
 * @property agentRoleSpawnableRoleDao DAO for the role-to-role spawn allow-list.
 * @property agentRoleOwnershipDao DAO for the `agent_role_owners` table (per-user ownership).
 * @property modelDao DAO used to validate model references.
 * @property settingsDao DAO used to validate settings references (existence, chat-capability, and model match).
 * @property toolDefinitionDao DAO used to validate tool references.
 * @property json Shared JSON codec used to (de)serialize the `instructions_json` column.
 * @property transactionScope Transaction wrapper that keeps validation + persistence atomic.
 */
class AgentRoleServiceImpl(
    private val agentRoleDao: AgentRoleDao,
    private val agentRoleToolDao: AgentRoleToolDao,
    private val agentRoleOwnershipDao: AgentRoleOwnershipDao,
    private val modelDao: ModelDao,
    private val settingsDao: SettingsDao,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val json: Json,
    private val transactionScope: TransactionScope,
    private val agentRoleSpawnableRoleDao: AgentRoleSpawnableRoleDao
) : AgentRoleService {

    companion object {
        /** Logger used for service-level diagnostics. */
        private val logger: Logger = LogManager.getLogger(AgentRoleServiceImpl::class.java)

        /** Maximum allowed length of the role name. */
        private const val MAX_NAME_LENGTH: Int = 255

        /** Error factories for the create flow ([CreateAgentRoleError] surface). */
        private val createValidationErrors = RoleValidationErrors(
            invalidName = { name, reason -> CreateAgentRoleError.InvalidName(name, reason) },
            modelNotFound = { modelId -> CreateAgentRoleError.ModelNotFound(modelId) },
            settingsNotFound = { settingsId -> CreateAgentRoleError.SettingsNotFound(settingsId) },
            settingsNotChatLike = { settingsId, actualType ->
                CreateAgentRoleError.SettingsNotChatLike(settingsId, actualType)
            },
            settingsModelMismatch = { settingsId, settingsModelId, roleModelId ->
                CreateAgentRoleError.SettingsModelMismatch(settingsId, settingsModelId, roleModelId)
            },
            toolNotFound = { toolId -> CreateAgentRoleError.ToolNotFound(toolId) },
            spawnableRoleNotFound = { roleId -> CreateAgentRoleError.SpawnableRoleNotFound(roleId) },
            instructionValidationFailed = { reason -> CreateAgentRoleError.InstructionValidationFailed(reason) }
        )

        /** Error factories for the update flow ([UpdateAgentRoleError] surface). */
        private val updateValidationErrors = RoleValidationErrors(
            invalidName = { name, reason -> UpdateAgentRoleError.InvalidName(name, reason) },
            modelNotFound = { modelId -> UpdateAgentRoleError.ModelNotFound(modelId) },
            settingsNotFound = { settingsId -> UpdateAgentRoleError.SettingsNotFound(settingsId) },
            settingsNotChatLike = { settingsId, actualType ->
                UpdateAgentRoleError.SettingsNotChatLike(settingsId, actualType)
            },
            settingsModelMismatch = { settingsId, settingsModelId, roleModelId ->
                UpdateAgentRoleError.SettingsModelMismatch(settingsId, settingsModelId, roleModelId)
            },
            toolNotFound = { toolId -> UpdateAgentRoleError.ToolNotFound(toolId) },
            spawnableRoleNotFound = { roleId -> UpdateAgentRoleError.SpawnableRoleNotFound(roleId) },
            instructionValidationFailed = { reason -> UpdateAgentRoleError.InstructionValidationFailed(reason) }
        )
    }

    override suspend fun getAllRolesForUser(userId: Long): List<AgentRoleDto> = transactionScope.transaction {
        logger.debug("Retrieving agent roles for user $userId")
        val entities = agentRoleDao.getAllRolesForUser(userId)
        // Batch-load every role's tool ids in one query so the list endpoint avoids an N+1 read.
        val roleIds = entities.map { it.id }
        val toolsByRole = agentRoleToolDao.getToolsForRoles(roleIds)
        val spawnableByRole = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRoles(roleIds)
        entities.map {
            it.toAgentRole(
                tools = toolsByRole[it.id].orEmpty(),
                spawnableRoleIds = spawnableByRole[it.id].orEmpty(),
                ownerId = userId
            ).toDto()
        }
    }

    override suspend fun getRoleById(userId: Long, roleId: Long): Either<AgentRoleError.NotFound, AgentRoleDto> =
        transactionScope.transaction {
            either {
                val entity = loadOwnedRole(userId, roleId, AgentRoleError.NotFound(roleId))
                val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
                entity.toAgentRole(
                    tools = agentRoleToolDao.getToolsForRole(entity.id),
                    spawnableRoleIds = spawnableRoleIds,
                    ownerId = userId
                ).toDto()
            }
        }

    override suspend fun getRoleByName(userId: Long, name: String): Either<AgentRoleError.NotFoundByName, AgentRoleDto> =
        transactionScope.transaction {
            either {
                // The lookup is already user-scoped (names are only unique per user), so no separate
                // ownership verification is needed.
                val entity = withError({ _: AgentRoleDaoError.NotFoundByName -> AgentRoleError.NotFoundByName(name) }) {
                    agentRoleDao.getRoleByNameForUser(userId, name).bind()
                }
                val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
                entity.toAgentRole(
                    tools = agentRoleToolDao.getToolsForRole(entity.id),
                    spawnableRoleIds = spawnableRoleIds,
                    ownerId = userId
                ).toDto()
            }
        }

    override suspend fun getAgentRoleById(roleId: Long): Either<AgentRoleError.NotFound, AgentRole> =
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
                loadDomainRole(entity, ownerId)
            }
        }

    override suspend fun createRole(
        userId: Long,
        request: CreateAgentRoleRequest
    ): Either<CreateAgentRoleError, AgentRoleDto> = transactionScope.transaction {
        either {
            logger.info("Creating agent role '${request.name}' for user $userId")

            validateRoleRequest(
                errors = createValidationErrors,
                name = request.name,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId,
                toolIds = request.toolIds,
                spawnableAgentRoleIds = request.spawnableAgentRoleIds,
                instructions = request.instructions,
                userId = userId
            )

            // Names are unique per user (not globally): only the requesting user's roles matter, so
            // different users may freely reuse the same name.
            ensure(!agentRoleDao.roleNameExistsForUser(userId, request.name)) {
                CreateAgentRoleError.NameAlreadyExists(request.name)
            }

            val instructionsJson = encodeInstructions(request.instructions)

            val entity = agentRoleDao.insertRole(
                name = request.name,
                displayName = request.displayName,
                description = request.description,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId,
                instructionsJson = instructionsJson
            )

            // Persist the tool set in the join table (a full replacement of the new role's empty set),
            // atomically with the role row and its ownership inside the same transaction.
            agentRoleToolDao.replaceToolsForRole(entity.id, request.toolIds)
            agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(entity.id, request.spawnableAgentRoleIds)

            withError({ ownershipError: SetOwnerError ->
                CreateAgentRoleError.OwnerInsertFailed(ownershipError.toString())
            }) {
                agentRoleOwnershipDao.setOwner(entity.id, userId).bind()
            }

            logger.info("Created agent role '${request.name}' (id ${entity.id}) for user $userId")
            entity.toAgentRole(
                tools = request.toolIds,
                spawnableRoleIds = request.spawnableAgentRoleIds,
                ownerId = userId
            ).toDto()
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

            validateRoleRequest(
                errors = updateValidationErrors,
                name = request.name,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId,
                toolIds = request.toolIds,
                spawnableAgentRoleIds = request.spawnableAgentRoleIds,
                instructions = request.instructions,
                userId = userId
            )

            // Name uniqueness is scoped per user (not globally). The role being updated is excluded
            // implicitly: it still carries its old name here, so a conflict means a DIFFERENT role of
            // the same user owns the requested name.
            if (request.name != existing.name) {
                ensure(!agentRoleDao.roleNameExistsForUser(userId, request.name)) {
                    UpdateAgentRoleError.NameAlreadyExists(request.name)
                }
            }

            val updated = existing.copy(
                name = request.name,
                displayName = request.displayName,
                description = request.description,
                modelId = request.modelId,
                modelSettingsId = request.modelSettingsId,
                instructionsJson = encodeInstructions(request.instructions)
            )

            withError({ _: AgentRoleDaoError.NotFound -> UpdateAgentRoleError.NotFound(roleId) }) {
                agentRoleDao.updateRole(updated).bind()
            }

            // Full-replacement semantics preserved: the tool set is rewritten atomically with the role
            // row (delete + insert) inside the same transaction.
            agentRoleToolDao.replaceToolsForRole(roleId, request.toolIds)
            agentRoleSpawnableRoleDao.replaceSpawnableRolesForRole(roleId, request.spawnableAgentRoleIds)

            logger.info("Updated agent role $roleId for user $userId")
            updated.toAgentRole(
                tools = request.toolIds,
                spawnableRoleIds = request.spawnableAgentRoleIds,
                ownerId = userId
            ).toDto()
        }
    }

    override suspend fun deleteRole(userId: Long, roleId: Long): Either<DeleteAgentRoleError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Deleting agent role $roleId for user $userId")

                withError({ _: AgentRoleDaoError.NotFound -> DeleteAgentRoleError.NotFound(roleId) }) {
                    agentRoleDao.deleteRole(roleId).bind()
                }

                logger.info("Deleted agent role $roleId for user $userId")
            }
        }

    // --- Validation helpers ---

    /**
     * Validates all shared role configuration invariants: name shape, model existence, settings
     * existence + chat-capability + model consistency, tool ownership, and instruction-list rules.
     *
     * The exact error subtype raised is decoupled through [errors], letting this single helper serve
     * both the create and update flows (which use different error surfaces).
     *
     * @param errors Factories that map each validation failure to the caller's error type.
     * @param name The role name to validate.
     * @param modelId The model identifier to validate; null is allowed (a role without a model is
     *            non-sendable until a model is set via update).
     * @param modelSettingsId The settings identifier to validate; null is allowed (a role without
     *            settings is non-sendable until settings are set via update).
     * @param toolIds The tool identifiers to validate; every id must belong to [userId]'s owned
     *            tool set (MCP tools of the user's servers, built-in tools of the user's workers,
     *            and the user's operator/server built-in rows). A missing or foreign id raises the
     *            same not-found error, so an attach attempt cannot be told apart from a plain
     *            non-existent id.
     * @param spawnableAgentRoleIds Target role identifiers to validate; duplicates are impossible at
     *            the wire level (a set) and self-referencing is allowed.
     * @param instructions The instruction DTOs to validate.
     * @param userId User whose role and tool ownership is required.
     * @return `null` on success or an error of type `E` via the raise scope.
     */
    private suspend fun <E> Raise<E>.validateRoleRequest(
        errors: RoleValidationErrors<E>,
        name: String,
        modelId: Long?,
        modelSettingsId: Long?,
        toolIds: Set<Long>,
        spawnableAgentRoleIds: Set<Long>,
        instructions: List<AgentInstructionDto>,
        userId: Long
    ) {
        ensure(name.isNotBlank()) {
            errors.invalidName(name, "Role name cannot be blank")
        }
        ensure(name.length <= MAX_NAME_LENGTH) {
            errors.invalidName(name, "Role name cannot exceed $MAX_NAME_LENGTH characters")
        }

        // Model and settings references are validated individually and only when provided: a role
        // may be created or updated without a model/settings (it is non-sendable until repaired),
        // so the checks must not run for null references.
        if (modelId != null) {
            withError({ _: ModelError.ModelNotFound -> errors.modelNotFound(modelId) }) {
                modelDao.getModelById(modelId).bind()
            }
        }

        if (modelSettingsId != null) {
            val settings = withError({ _: SettingsError.SettingsNotFound -> errors.settingsNotFound(modelSettingsId) }) {
                settingsDao.getSettingsById(modelSettingsId).bind()
            }
            ensure(isChatLikeSettings(settings)) {
                errors.settingsNotChatLike(modelSettingsId, settings::class.simpleName ?: "Unknown")
            }
            // The model↔settings consistency check only makes sense when both references are
            // provided; a model-less role has nothing to match its settings against.
            if (modelId != null) {
                ensure(settings.modelId == modelId) {
                    errors.settingsModelMismatch(modelSettingsId, settings.modelId, modelId)
                }
            }
        }

        // Every tool is user-owned: MCP tools via their server's owner, worker built-ins via the
        // worker's owner, and operator/server built-in tools via the per-user linkage row. Rather
        // than checking each type separately, the whole id set is validated against the user's owned
        // tool set (the same four owner-scoped joins the tool listing uses), so a guessed or foreign
        // id — of any type — collapses to the same not-found error as a plain non-existent id.
        if (toolIds.isNotEmpty()) {
            val userToolIds = toolDefinitionDao.getToolsForUser(userId).map { it.id }.toSet()
            toolIds.firstOrNull { it !in userToolIds }?.let { missingId ->
                raise(errors.toolNotFound(missingId))
            }
        }

        // Targets must exist and belong to the requesting user; the set wire shape already rules out
        // duplicates and self-referencing is intentionally allowed, so only ownership is checked here.
        if (spawnableAgentRoleIds.isNotEmpty()) {
            val ownedTargetIds = agentRoleDao
                .getRolesByIdsForUser(userId, spawnableAgentRoleIds.toList())
                .map { it.id }
                .toSet()
            spawnableAgentRoleIds.firstOrNull { it !in ownedTargetIds }?.let { missingId ->
                raise(errors.spawnableRoleNotFound(missingId))
            }
        }

        val roleCount = instructions.count { it.type == AgentInstructionTypes.ROLE }
        val mainCount = instructions.count { it.type == AgentInstructionTypes.MAIN }
        val spawnableInstructionCount = instructions.count { it.type == AgentInstructionTypes.SPAWNABLE_AGENTS }
        ensure(roleCount <= 1) {
            errors.instructionValidationFailed("At most one 'role' instruction is allowed")
        }
        ensure(mainCount <= 1) {
            errors.instructionValidationFailed("At most one 'main' instruction is allowed")
        }
        ensure(spawnableInstructionCount <= 1) {
            errors.instructionValidationFailed("At most one 'spawnable_agents' instruction is allowed")
        }

        // `model_specific` is multi-instance (one per target model) but each instance must reference a
        // distinct model: two entries for the same model would be redundant and ambiguous at compose
        // time, where the composer keeps only the matching instance.
        val modelSpecificModelIds = instructions
            .filter { it.type == AgentInstructionTypes.MODEL_SPECIFIC }
            .mapNotNull { it.modelSpecificId() }
        ensure(modelSpecificModelIds.distinct().size == modelSpecificModelIds.size) {
            errors.instructionValidationFailed(
                "Each 'model_specific' instruction must reference a distinct model"
            )
        }
    }

    /**
     * Whether the given [ModelSettings] is chat-capable (CHAT or RESPONSES).
     *
     * @param settings The settings profile to inspect.
     * @return `true` for chat-capable settings, `false` otherwise.
     */
    private fun isChatLikeSettings(settings: ModelSettings): Boolean = when (settings) {
        is ChatModelSettings -> true
        is ResponsesModelSettings -> true
        else -> false
    }

    /**
     * Factories mapping each role-validation failure to a caller-specific error type.
     *
     * @property invalidName Builds an invalid-name error.
     * @property modelNotFound Builds a model-not-found error.
     * @property settingsNotFound Builds a settings-not-found error.
     * @property settingsNotChatLike Builds a settings-not-chat-capable error.
     * @property settingsModelMismatch Builds a settings/model mismatch error.
     * @property toolNotFound Builds a tool-not-found error.
     * @property spawnableRoleNotFound Builds an inaccessible-target error.
     * @property instructionValidationFailed Builds an instruction-validation error.
     */
    private data class RoleValidationErrors<E>(
        val invalidName: (name: String, reason: String) -> E,
        val modelNotFound: (modelId: Long) -> E,
        val settingsNotFound: (settingsId: Long) -> E,
        val settingsNotChatLike: (settingsId: Long, actualType: String) -> E,
        val settingsModelMismatch: (settingsId: Long, settingsModelId: Long, roleModelId: Long) -> E,
        val toolNotFound: (toolId: Long) -> E,
        val spawnableRoleNotFound: (roleId: Long) -> E,
        val instructionValidationFailed: (reason: String) -> E
    )

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

    // --- Mapping / serialization helpers ---

    /**
     * Loads normalized role relations and maps a stored row into its domain representation.
     *
     * @param entity Stored role row.
     * @param ownerId Owner scope used by dynamic instruction loaders.
     * @return Domain role with current relation ids and lazy instruction sources.
     */
    private suspend fun loadDomainRole(entity: AgentRoleEntity, ownerId: Long): AgentRole {
        val spawnableRoleIds = agentRoleSpawnableRoleDao.getSpawnableRoleIdsForRole(entity.id)
        return entity.toAgentRole(
            tools = agentRoleToolDao.getToolsForRole(entity.id),
            spawnableRoleIds = spawnableRoleIds,
            ownerId = ownerId
        )
    }

    /**
     * Converts a stored role into the domain type while retaining current, ownership-scoped prompt
     * loaders for dynamic instructions.
     *
     * @receiver Stored role row to convert.
     * @param tools Attached tool ids.
     * @param spawnableRoleIds Unordered target role ids.
     * @param ownerId Owner used to scope dynamic target-summary queries.
     * @return Domain role with lazy instruction sources.
     */
    private fun AgentRoleEntity.toAgentRole(
        tools: Set<Long>,
        spawnableRoleIds: Set<Long>,
        ownerId: Long
    ): AgentRole = AgentRole(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        tools = tools,
        spawnableAgentRoleIds = spawnableRoleIds,
        instructions = decodeInstructions(instructionsJson).mapNotNull {
            it.toDomain(
                ownerId = ownerId,
                spawnableRoleIds = spawnableRoleIds,
                roleToolIds = tools
            )
        }
    )

    /**
     * Converts a server domain [AgentRole] into a wire [AgentRoleDto], resolving every instruction
     * message first so the DTO always carries non-null, current text.
     *
     * @receiver The domain role to convert.
     * @return The corresponding [AgentRoleDto].
     */
    private suspend fun AgentRole.toDto(): AgentRoleDto = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        tools = tools,
        spawnableAgentRoleIds = spawnableAgentRoleIds,
        instructions = instructions.map { it.toDto() }
    )

    /**
     * Maps an [AgentInstructionDto] to its server domain subtype.
     *
     * Dispatches on the DTO's [AgentInstructionDto.type] string (a flat, non-polymorphic DTO).
     * The `else` branch logs a warning and returns null for unknown or unrecognized kinds,
     * so forward-compatible payloads don't silently apply unrecognized semantics.
     *
     * @receiver The DTO to map.
     * @param ownerId Owner scope for dynamic target-summary resolution.
     * @param spawnableRoleIds Unordered target ids used by the dynamic marker.
     * @param roleToolIds Tool ids used to determine whether `spawn_agent` is enabled.
     * @return The corresponding [AgentInstruction], or null when the kind is unrecognized or a
     *         `model_specific` instruction is missing its `modelId` in `custom` (both logged as
     *         warnings — they indicate a database inconsistency).
     */
    private fun AgentInstructionDto.toDomain(
        ownerId: Long,
        spawnableRoleIds: Set<Long>,
        roleToolIds: Set<Long>
    ): AgentInstruction? = when (type) {
        AgentInstructionTypes.SPAWNABLE_AGENTS -> SpawnableAgentsInstruction(
            name = name,
            roleSummaryLoader = {
                // The allow-list is a set, so no persisted order exists; sort by name to keep the
                // generated prompt deterministic across reads.
                agentRoleDao.getRolesByIdsForUser(ownerId, spawnableRoleIds.toList())
                    .sortedBy { it.name.lowercase() }
                    .map { target ->
                        AgentRoleSummary(
                            id = target.id,
                            name = target.name,
                            displayName = target.displayName,
                            description = target.description
                        )
                    }
            },
            spawnAgentToolAvailableLoader = {
                toolDefinitionDao.getToolDefinitionsByIds(roleToolIds)
                    .any { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
            }
        )

        AgentInstructionTypes.ROLE -> RoleInstruction(name, message)
        AgentInstructionTypes.MAIN -> MainInstruction(name, message)
        AgentInstructionTypes.CUSTOM -> CustomInstruction(name, message)
        AgentInstructionTypes.MODEL_SPECIFIC -> {
            val targetModelId = modelSpecificId()
            if (targetModelId == null) {
                // A model_specific instruction without a modelId is a data integrity issue: the
                // stored JSON was malformed or partially migrated. Log it and drop the instruction
                // rather than crashing role retrieval.
                logger.warn(
                    "Dropping model_specific instruction '{}' for role retrieval: missing 'modelId' in custom",
                    name
                )
                null
            } else {
                ModelSpecificInstruction(
                    name = name,
                    message = message,
                    modelId = targetModelId
                )
            }
        }
        // Unknown/unrecognized kinds: log and drop rather than silently applying them as generic
        // text, so data integrity issues surface instead of being hidden.
        else -> {
            logger.warn(
                "Dropping unrecognized instruction '{}' (type '{}') for role retrieval:"
                + " unrecognized kind",
                name,
                type
            )
            null
        }
    }

    /**
     * Converts a domain [AgentInstruction] into a wire [AgentInstructionDto], resolving its message.
     *
     * The `when` covers all known domain subtypes; the `else` throws an `IllegalStateException`
     * for unknown subtypes, since encountering one indicates a programming error (a new subtype
     * was added without updating this mapping).
     *
     * @receiver The domain instruction to convert.
     * @return The corresponding [AgentInstructionDto] with a resolved [AgentInstructionDto.message].
     */
    private suspend fun AgentInstruction.toDto(): AgentInstructionDto {
        loadMessage()
        return when (this) {
            is SpawnableAgentsInstruction ->
                AgentInstructionDto(AgentInstructionTypes.SPAWNABLE_AGENTS, name, message)
            is RoleInstruction ->
                AgentInstructionDto(AgentInstructionTypes.ROLE, name, message)
            is MainInstruction ->
                AgentInstructionDto(AgentInstructionTypes.MAIN, name, message)
            is CustomInstruction ->
                AgentInstructionDto(AgentInstructionTypes.CUSTOM, name, message)
            is ModelSpecificInstruction ->
                AgentInstructionDto(
                    type = AgentInstructionTypes.MODEL_SPECIFIC,
                    name = name,
                    message = message,
                    custom = buildJsonObject { put("modelId", modelId) }
                )

            else -> error("Unknown AgentInstruction subtype: ${this::class.simpleName}")
        }
    }

    /**
     * Serializes an instruction DTO list into its JSON column representation (the wire shape).
     *
     * @param instructions The instruction DTOs.
     * @return The JSON array string.
     */
    private fun encodeInstructions(instructions: List<AgentInstructionDto>): String = json.encodeToString(instructions)

    /**
     * Deserializes the `instructions_json` column into the instruction DTO list (the wire shape).
     *
     * @param instructionsJson The JSON array string.
     * @return The instruction DTOs; an empty list when the stored value is unparseable.
     */
    private fun decodeInstructions(instructionsJson: String): List<AgentInstructionDto> =
        runCatching { json.decodeFromString<List<AgentInstructionDto>>(instructionsJson) }.getOrDefault(emptyList())
}
