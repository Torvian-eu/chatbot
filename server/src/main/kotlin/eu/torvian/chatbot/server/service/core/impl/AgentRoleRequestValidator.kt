package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelSpecificId
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import eu.torvian.chatbot.server.service.llm.isChatLikeSettings

/**
 * Shared request validation for agent-role writes: name shape, the attached model preset, tool
 * ownership, spawn allow-list membership, project membership and instruction-list rules.
 *
 * The validation is transaction-free and never logs; it only reads the five DAOs below. The exact
 * error subtype raised is decoupled through [RoleValidationErrors], letting the single private core
 * serve both the create and update flows (which use different error surfaces). The two entry points
 * return `Either` and are consumed with `.bind()` at the call site, mirroring
 * `LocalMCPServerServiceImpl.validateRequest(...).bind()`; the `Raise`-based core stays private per
 * the Arrow typed-error conventions.
 *
 * @property agentRoleDao DAO used to validate spawn allow-list targets (existence, ownership and
 *            persisted project scope).
 * @property modelPresetDao DAO used to resolve and validate an attached preset (ownership-scoped
 *            read doubling as the ownership check).
 * @property settingsDao DAO used to validate an attached preset's settings reference
 *            (chat-capability and agreement with the preset's model).
 * @property toolDefinitionDao DAO used to validate tool references against the user's owned tool set.
 * @property projectDao DAO used to validate project references (ownership and existence).
 */
internal class AgentRoleRequestValidator(
    private val agentRoleDao: AgentRoleDao,
    private val modelPresetDao: ModelPresetDao,
    private val settingsDao: SettingsDao,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val projectDao: ProjectDao
) {
    companion object {
        /** Maximum allowed length of the role name. */
        private const val MAX_NAME_LENGTH: Int = 255

        /** Error factories for the create flow ([CreateAgentRoleError] surface). */
        private val createValidationErrors = RoleValidationErrors(
            invalidName = { name, reason -> CreateAgentRoleError.InvalidName(name, reason) },
            modelPresetNotFound = { presetId -> CreateAgentRoleError.ModelPresetNotFound(presetId) },
            modelPresetNotChatLike = { presetId, settingsId, actualType ->
                CreateAgentRoleError.ModelPresetNotChatLike(presetId, settingsId, actualType)
            },
            modelPresetSettingsModelMismatch = { presetId, presetModelId, settingsModelId ->
                CreateAgentRoleError.ModelPresetSettingsModelMismatch(presetId, presetModelId, settingsModelId)
            },
            toolNotFound = { toolId -> CreateAgentRoleError.ToolNotFound(toolId) },
            spawnableRoleNotFound = { roleId -> CreateAgentRoleError.SpawnableRoleNotFound(roleId) },
            spawnableRoleNotInProject = { roleId, projectId ->
                CreateAgentRoleError.SpawnableRoleNotInProject(roleId, projectId)
            },
            projectNotFound = { projectId -> CreateAgentRoleError.ProjectNotFound(projectId) },
            instructionValidationFailed = { reason -> CreateAgentRoleError.InstructionValidationFailed(reason) }
        )

        /** Error factories for the update flow ([UpdateAgentRoleError] surface). */
        private val updateValidationErrors = RoleValidationErrors(
            invalidName = { name, reason -> UpdateAgentRoleError.InvalidName(name, reason) },
            modelPresetNotFound = { presetId -> UpdateAgentRoleError.ModelPresetNotFound(presetId) },
            modelPresetNotChatLike = { presetId, settingsId, actualType ->
                UpdateAgentRoleError.ModelPresetNotChatLike(presetId, settingsId, actualType)
            },
            modelPresetSettingsModelMismatch = { presetId, presetModelId, settingsModelId ->
                UpdateAgentRoleError.ModelPresetSettingsModelMismatch(presetId, presetModelId, settingsModelId)
            },
            toolNotFound = { toolId -> UpdateAgentRoleError.ToolNotFound(toolId) },
            spawnableRoleNotFound = { roleId -> UpdateAgentRoleError.SpawnableRoleNotFound(roleId) },
            spawnableRoleNotInProject = { roleId, projectId ->
                UpdateAgentRoleError.SpawnableRoleNotInProject(roleId, projectId)
            },
            projectNotFound = { projectId -> UpdateAgentRoleError.ProjectNotFound(projectId) },
            instructionValidationFailed = { reason -> UpdateAgentRoleError.InstructionValidationFailed(reason) }
        )
    }

    /**
     * Validates a create-role request against all shared role configuration invariants.
     *
     * A new role cannot be its own spawn target, so the self-spawn exemption id is null.
     *
     * @param userId User whose role, preset, tool and project ownership is required.
     * @param request The create request to validate.
     * @return The resolved preset entity (null when no preset is attached), so the caller can build
     *         the derived model/settings ids without a second read.
     */
    suspend fun validateCreate(userId: Long, request: CreateAgentRoleRequest): Either<CreateAgentRoleError, ModelPresetEntity?> =
        either {
            validate(
                errors = createValidationErrors,
                name = request.name,
                modelPresetId = request.modelPresetId,
                toolIds = request.toolIds,
                spawnableAgentRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                roleId = null,
                instructions = request.instructions,
                userId = userId
            )
        }

    /**
     * Validates an update-role request against all shared role configuration invariants.
     *
     * @param userId User whose role, preset, tool and project ownership is required.
     * @param roleId The role's own id, used to exempt self-spawn from the persisted (stale)
     *            membership comparison while the role moves projects.
     * @param request The update request to validate.
     * @return The resolved preset entity (null when no preset is attached), so the caller can build
     *         the derived model/settings ids without a second read.
     */
    suspend fun validateUpdate(
        userId: Long,
        roleId: Long,
        request: UpdateAgentRoleRequest
    ): Either<UpdateAgentRoleError, ModelPresetEntity?> =
        either {
            validate(
                errors = updateValidationErrors,
                name = request.name,
                modelPresetId = request.modelPresetId,
                toolIds = request.toolIds,
                spawnableAgentRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                roleId = roleId,
                instructions = request.instructions,
                userId = userId
            )
        }

    /**
     * Validates all shared role configuration invariants: name shape, the attached model preset,
     * tool ownership, and instruction-list rules.
     *
     * The exact error subtype raised is decoupled through [errors], letting this single helper serve
     * both the create and update flows (which use different error surfaces).
     *
     * @param errors Factories that map each validation failure to the caller's error type.
     * @param name The role name to validate.
     * @param modelPresetId The model preset to attach, or null for a preset-less role. Null is allowed
     *            (the role is then non-sendable until a preset is attached).
     * @param toolIds The tool identifiers to validate; every id must belong to [userId]'s owned
     *            tool set (MCP tools of the user's servers, built-in tools of the user's workers,
     *            and the user's operator/server built-in rows). A missing or foreign id raises the
     *            same not-found error, so an attach attempt cannot be told apart from a plain
     *            non-existent id.
     * @param spawnableAgentRoleIds Target role identifiers to validate; duplicates are impossible at
     *            the wire level (a set) and self-referencing is allowed. Every target must exist,
     *            belong to [userId], and have the **same project scope** as the role ([projectId]):
     *            the same project id, or both unassociated.
     * @param projectId The single project id the role belongs to; a non-null id must reference a
     *            user-owned project. A missing or foreign id raises the same not-found error.
     * @param roleId The role's own id while editing, used to exempt self-spawn from the persisted
     *            (stale) membership comparison; null on create.
     * @param instructions The instruction DTOs to validate.
     * @param userId User whose role and tool ownership is required.
     * @return The resolved preset entity (null when no preset is attached), so the caller can build the
     *         derived model/settings ids without a second read. Failures raise through the caller's
     *         `either { }` scope via the [Either]-returning entry points.
     */
    private suspend fun <E> Raise<E>.validate(
        errors: RoleValidationErrors<E>,
        name: String,
        modelPresetId: Long?,
        toolIds: Set<Long>,
        spawnableAgentRoleIds: Set<Long>,
        projectId: Long?,
        roleId: Long?,
        instructions: List<AgentInstructionDto>,
        userId: Long
    ): ModelPresetEntity? {
        ensure(name.isNotBlank()) {
            errors.invalidName(name, "Role name cannot be blank")
        }
        ensure(name.length <= MAX_NAME_LENGTH) {
            errors.invalidName(name, "Role name cannot exceed $MAX_NAME_LENGTH characters")
        }

        // The attached preset is validated only when one is supplied: a preset-less role is legal and
        // simply non-sendable, so the check must not run for `null`.
        val preset: ModelPresetEntity? = if (modelPresetId != null) {
            // A missing or foreign preset collapses to the same not-found error (no existence leak);
            // the owner-scoped batch read doubles as the ownership check.
            val resolved = modelPresetDao.getPresetsByIdsForUser(userId, listOf(modelPresetId)).singleOrNull()
                ?: raise(errors.modelPresetNotFound(modelPresetId))

            // Only non-null references are validated. A NULL model and/or settings reference is a legal
            // preset state — exactly what ON DELETE SET NULL produces when a referenced model or
            // settings row is deleted — so such a preset may be attached and simply yields a
            // non-sendable role.
            val presetSettingsId = resolved.modelSettingsId
            if (presetSettingsId != null) {
                // The FK guarantees the row exists, so a not-found here means the settings vanished
                // between the preset read and this load: a technical failure, not a logical error
                // (Arrow errors must not model technical failures).
                val settings = settingsDao.getSettingsById(presetSettingsId).fold(
                    { error ->
                        throw IllegalStateException(
                            "Settings $presetSettingsId referenced by model preset $modelPresetId " +
                                "not found after validation ($error)"
                        )
                    },
                    { it }
                )
                ensure(isChatLikeSettings(settings)) {
                    errors.modelPresetNotChatLike(
                        modelPresetId,
                        presetSettingsId,
                        settings::class.simpleName ?: "Unknown"
                    )
                }
                // The model reference itself needs no lookup: the preset's `model_id` FK guarantees the
                // model row exists, and the agreement check compares the preset's model with the model
                // the loaded settings profile actually belongs to. This also catches a settings profile
                // that was re-pointed to another model after the preset was written.
                val presetModelId = resolved.modelId
                if (presetModelId != null) {
                    ensure(settings.modelId == presetModelId) {
                        errors.modelPresetSettingsModelMismatch(modelPresetId, presetModelId, settings.modelId)
                    }
                }
            }
            resolved
        } else {
            null
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

        // Targets must exist, belong to the requesting user, and share the role's project scope:
        // an in-project role may only spawn roles of the same project, an unassociated role only
        // unassociated roles. The set wire shape already rules out duplicates and self-referencing
        // is intentionally allowed (a role spawned from itself is trivially same-scope because the
        // role's own membership is written with the same [projectId] in this transaction). The
        // owned-target load doubles as the membership source (the single `project_id` column rides
        // the loaded entities), so no separate project read is needed.
        if (spawnableAgentRoleIds.isNotEmpty()) {
            val ownedTargets = agentRoleDao.getRolesByIdsForUser(userId, spawnableAgentRoleIds.toList())
            val ownedTargetIds = ownedTargets.map { it.id }.toSet()
            spawnableAgentRoleIds.firstOrNull { it !in ownedTargetIds }?.let { missingId ->
                raise(errors.spawnableRoleNotFound(missingId))
            }
            // Same-project enforcement: every target must occupy exactly the role's project scope —
            // the same project id, or both null. Self-spawn is exempt from the persisted comparison:
            // the target IS the role being written, and its stored (stale) membership may legally
            // differ from [projectId] until this transaction writes the new one.
            ownedTargets
                .filterNot { it.id == roleId }
                .firstOrNull { it.projectId != projectId }
                ?.let { target -> raise(errors.spawnableRoleNotInProject(target.id, projectId)) }
        }

        // The role's project must be user-owned; a missing or foreign id collapses to the same
        // not-found error as a plain non-existent id (mirrors the tool/spawnable checks, no existence
        // leak). Null (unassociated) has nothing to check.
        if (projectId != null) {
            val ownedProjectIds = projectDao
                .getProjectsByIdsForUser(userId, listOf(projectId))
                .map { it.id }
                .toSet()
            if (projectId !in ownedProjectIds) {
                raise(errors.projectNotFound(projectId))
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

        // A `model_specific` instruction is meaningless without its target model: the composer
        // keeps only the instance matching the active model, so a missing target would be silently
        // dropped at read time. Reject it up front instead of accepting data that disappears.
        ensure(instructions.none {
            it.type == AgentInstructionTypes.MODEL_SPECIFIC && it.modelSpecificId() == null
        }) {
            errors.instructionValidationFailed(
                "A 'model_specific' instruction must include custom.modelId"
            )
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

        return preset
    }

    /**
     * Factories mapping each role-validation failure to a caller-specific error type.
     *
     * @property invalidName Builds an invalid-name error.
     * @property modelPresetNotFound Builds a model-preset-not-found error.
     * @property modelPresetNotChatLike Builds an attached-preset-not-chat-capable error.
     * @property modelPresetSettingsModelMismatch Builds an attached-preset/settings-model mismatch error.
     * @property toolNotFound Builds a tool-not-found error.
     * @property spawnableRoleNotFound Builds an inaccessible-target error.
     * @property spawnableRoleNotInProject Builds a same-project-spawn enforcement error (a target
     *            role does not occupy the source role's project scope).
     * @property projectNotFound Builds a project-not-found error.
     * @property instructionValidationFailed Builds an instruction-validation error.
     */
    private data class RoleValidationErrors<E>(
        val invalidName: (name: String, reason: String) -> E,
        val modelPresetNotFound: (presetId: Long) -> E,
        val modelPresetNotChatLike: (presetId: Long, settingsId: Long, actualType: String) -> E,
        val modelPresetSettingsModelMismatch: (presetId: Long, presetModelId: Long, settingsModelId: Long) -> E,
        val toolNotFound: (toolId: Long) -> E,
        val spawnableRoleNotFound: (roleId: Long) -> E,
        val spawnableRoleNotInProject: (roleId: Long, projectId: Long?) -> E,
        val projectNotFound: (projectId: Long) -> E,
        val instructionValidationFailed: (reason: String) -> E
    )
}
