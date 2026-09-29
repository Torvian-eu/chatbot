package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.data.dao.AgentRoleDao
import eu.torvian.chatbot.server.data.dao.InstructionDao
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.ProjectDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.ToolDefinitionDao
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.error.agent.CreateAgentRoleError
import eu.torvian.chatbot.server.service.core.error.agent.UpdateAgentRoleError
import eu.torvian.chatbot.server.service.llm.isChatLikeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Shared request validation for agent-role writes: name shape, the attached model preset, tool
 * ownership, spawn allow-list membership, project membership and instruction-list rules.
 *
 * The validation is transaction-free and never logs; it only reads the DAOs below. The exact
 * error subtype raised is decoupled through [RoleValidationErrors], letting the single private core
 * serve both the create and update flows (which use different error surfaces). The two entry points
 * return `Either` and are consumed with `.bind()` at the call site, mirroring
 * `LocalMCPServerServiceImpl.validateRequest(...).bind()`; the `Raise`-based core stays private per
 * the Arrow typed-error conventions.
 *
 * Instruction specs are validated as the role's full ordered link set: inline content is checked
 * with [InstructionContentRules] (the instruction endpoints' rules), link-level checks (no row twice
 * in one request, every referenced row exists and is owned by the caller) live here, and the
 * per-list rules live in [AgentRoleInstructionRules].
 *
 * @property agentRoleDao DAO used to validate spawn allow-list targets (existence and ownership).
 * @property modelPresetDao DAO used to resolve and validate an attached preset (ownership-scoped
 *            read doubling as the ownership check).
 * @property settingsDao DAO used to validate an attached preset's settings reference
 *            (chat-capability and agreement with the preset's model).
 * @property toolDefinitionDao DAO used to validate tool references against the user's owned tool set.
 * @property projectDao DAO used to validate project references (ownership and existence).
 * @property instructionDao DAO used to validate linked instruction references (existence and
 *            ownership).
 */
internal class AgentRoleRequestValidator(
    private val agentRoleDao: AgentRoleDao,
    private val modelPresetDao: ModelPresetDao,
    private val settingsDao: SettingsDao,
    private val toolDefinitionDao: ToolDefinitionDao,
    private val projectDao: ProjectDao,
    private val instructionDao: InstructionDao,
    private val json: Json
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
            projectNotFound = { projectId -> CreateAgentRoleError.ProjectNotFound(projectId) },
            instructionValidationFailed = { reason -> CreateAgentRoleError.InstructionValidationFailed(reason) },
            duplicateInstructionLink = { instructionId ->
                CreateAgentRoleError.DuplicateInstructionLink(instructionId)
            },
            instructionNotFound = { instructionId -> CreateAgentRoleError.InstructionNotFound(instructionId) }
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
            projectNotFound = { projectId -> UpdateAgentRoleError.ProjectNotFound(projectId) },
            instructionValidationFailed = { reason -> UpdateAgentRoleError.InstructionValidationFailed(reason) },
            duplicateInstructionLink = { instructionId ->
                UpdateAgentRoleError.DuplicateInstructionLink(instructionId)
            },
            instructionNotFound = { instructionId -> UpdateAgentRoleError.InstructionNotFound(instructionId) }
        )
    }

    /**
     * Validates a create-role request against all shared role configuration invariants.
     *
     * @param userId User whose role, preset, tool and project ownership is required.
     * @param request The create request to validate.
     * @return The validated write set: the resolved preset (null when none is attached) and the
     *         referenced instruction rows, so the write phase needs no second read.
     */
    suspend fun validateCreate(userId: Long, request: CreateAgentRoleRequest): Either<CreateAgentRoleError, ValidatedRoleWrite> =
        either {
            validate(
                errors = createValidationErrors,
                name = request.name,
                modelPresetId = request.modelPresetId,
                toolIds = request.toolIds,
                spawnableAgentRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                instructionSpecs = request.instructionSpecs,
                userId = userId
            )
        }

    /**
     * Validates an update-role request against all shared role configuration invariants.
     *
     * @param userId User whose role, preset, tool and project ownership is required.
     * @param request The update request to validate.
     * @return The validated write set: the resolved preset (null when none is attached) and the
     *         referenced instruction rows, so the write phase needs no second read.
     */
    suspend fun validateUpdate(
        userId: Long,
        request: UpdateAgentRoleRequest
    ): Either<UpdateAgentRoleError, ValidatedRoleWrite> =
        either {
            validate(
                errors = updateValidationErrors,
                name = request.name,
                modelPresetId = request.modelPresetId,
                toolIds = request.toolIds,
                spawnableAgentRoleIds = request.spawnableAgentRoleIds,
                projectId = request.projectId,
                instructionSpecs = request.instructionSpecs,
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
     *            the wire level (a set) and self-referencing is allowed. Every target must exist and
     *            belong to [userId]; targets may belong to any project scope of that user.
     * @param projectId The single project id the role belongs to; a non-null id must reference a
     *            user-owned project. A missing or foreign id raises the same not-found error. It never
     *            constrains the spawn allow-list.
     * @param instructionSpecs The role's full ordered link set (reference, create, or replace-and-link
     *            per entry).
     * @param userId User whose role and tool ownership is required.
     * @return The validated write set: the resolved preset (null when none is attached) and the
     *         referenced instruction rows, so the write phase needs no second read. Failures raise
     *         through the caller's `either { }` scope via the [Either]-returning entry points.
     */
    private suspend fun <E> Raise<E>.validate(
        errors: RoleValidationErrors<E>,
        name: String,
        modelPresetId: Long?,
        toolIds: Set<Long>,
        spawnableAgentRoleIds: Set<Long>,
        projectId: Long?,
        instructionSpecs: List<InstructionSlot>,
        userId: Long
    ): ValidatedRoleWrite {
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

        // Every target must exist and belong to the requesting user; the set wire shape rules out
        // duplicate ids and self-referencing is intentionally allowed. Targets may live in any of the
        // user's project scopes, so project membership is deliberately not compared here: a spawn target
        // is addressed by its id at runtime, which cannot be ambiguous. The owned-target load doubles as
        // the ownership check.
        if (spawnableAgentRoleIds.isNotEmpty()) {
            val ownedTargets = agentRoleDao.getRolesByIdsForUser(userId, spawnableAgentRoleIds.toList())
            val ownedTargetIds = ownedTargets.map { it.id }.toSet()
            spawnableAgentRoleIds.firstOrNull { it !in ownedTargetIds }?.let { missingId ->
                raise(errors.spawnableRoleNotFound(missingId))
            }
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

        // Instruction specs are the role's full ordered link set. A row may appear at most once per
        // role (the composite PK enforces it at the storage level too): `Link` and `Update` each
        // materialize one link, so their target ids share one duplicate check.
        val referencedIds = instructionSpecs.mapNotNull { slot ->
            when (slot) {
                is InstructionSlot.Link -> slot.id
                is InstructionSlot.Create -> null
                is InstructionSlot.Update -> slot.content.id
            }
        }
        referencedIds.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { duplicate ->
            raise(errors.duplicateInstructionLink(duplicate.key))
        }
        // Missing and foreign ids collapse to the same not-found error (no existence leak); the
        // owner-scoped batch read doubles as the ownership check and feeds the write phase, so an
        // `Update` spec needs no second read of its target row.
        val referencedById = if (referencedIds.isEmpty()) {
            emptyMap()
        } else {
            instructionDao
                .getInstructionsByIdsForUser(userId, referencedIds.distinct())
                .associateBy { it.id }
        }
        referencedIds.firstOrNull { it !in referencedById }?.let { missingId ->
            raise(errors.instructionNotFound(missingId))
        }

        // Inline content is validated exactly like the instruction endpoints (`Create` like the
        // create endpoint, `Update` like the update endpoint). The per-role rules then judge the
        // resulting list in request order: the rule input comes from the loaded row for `Link` and
        // from the authored content for the inline variants.
        val ruleInputs = instructionSpecs.map { slot ->
            when (slot) {
                is InstructionSlot.Link -> referencedById.getValue(slot.id).toRuleInput(json)
                is InstructionSlot.Create -> validatedContentRuleInput(
                    errors = errors,
                    type = slot.content.type,
                    name = slot.content.name,
                    message = slot.content.message,
                    custom = slot.content.custom
                )

                is InstructionSlot.Update -> validatedContentRuleInput(
                    errors = errors,
                    type = slot.content.type,
                    name = slot.content.name,
                    message = slot.content.message,
                    custom = slot.content.custom
                )
            }
        }
        AgentRoleInstructionRules.validate(
            instructions = ruleInputs,
            raise = this,
            instructionValidationFailed = errors.instructionValidationFailed
        )

        return ValidatedRoleWrite(preset = preset, referencedInstructions = referencedById)
    }

    /**
     * Validates one inline spec's authored content and projects it into the per-role rule input.
     *
     * Runs the shared instruction content rules first, so inline content is classified with the
     * instruction endpoints' wording before the list-level rules see it.
     *
     * @receiver The raise scope of the caller.
     * @param errors Factories that map each validation failure to the caller's error type.
     * @param type The instruction kind key.
     * @param name The instruction label.
     * @param message The instruction text, or null for the generated-message kind.
     * @param custom Type-specific extra fields.
     * @return The rule input describing the authored kind and model target.
     */
    private fun <E> Raise<E>.validatedContentRuleInput(
        errors: RoleValidationErrors<E>,
        type: String,
        name: String,
        message: String?,
        custom: JsonObject?
    ): AgentRoleInstructionRules.RuleInput {
        InstructionContentRules.validate(
            type = type,
            name = name,
            message = message,
            custom = custom,
            raise = this,
            validationFailed = errors.instructionValidationFailed
        )
        return AgentRoleInstructionRules.RuleInput(type = type, modelId = custom.modelIdOrNull())
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
     * @property projectNotFound Builds a project-not-found error.
     * @property instructionValidationFailed Builds an instruction-validation error.
     * @property duplicateInstructionLink Builds a duplicate-instruction-link error.
     * @property instructionNotFound Builds an instruction-not-found error.
     */
    private data class RoleValidationErrors<E>(
        val invalidName: (name: String, reason: String) -> E,
        val modelPresetNotFound: (presetId: Long) -> E,
        val modelPresetNotChatLike: (presetId: Long, settingsId: Long, actualType: String) -> E,
        val modelPresetSettingsModelMismatch: (presetId: Long, presetModelId: Long, settingsModelId: Long) -> E,
        val toolNotFound: (toolId: Long) -> E,
        val spawnableRoleNotFound: (roleId: Long) -> E,
        val projectNotFound: (projectId: Long) -> E,
        val instructionValidationFailed: (reason: String) -> E,
        val duplicateInstructionLink: (instructionId: Long) -> E,
        val instructionNotFound: (instructionId: Long) -> E
    )
}

/**
 * Outcome of a validated role write, consumed by the materialization phase.
 *
 * @property preset The resolved preset entity, or null when the role is preset-less.
 * @property referencedInstructions The `Link`/`Update` target rows loaded during validation, keyed by
 *            row id, so materializing an `Update` spec needs no second read.
 */
internal data class ValidatedRoleWrite(
    val preset: ModelPresetEntity?,
    val referencedInstructions: Map<Long, InstructionEntity>
)
