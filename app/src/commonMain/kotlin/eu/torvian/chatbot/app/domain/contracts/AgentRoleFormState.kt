package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest

/**
 * Mutable draft of an agent role being created or edited in the management form.
 *
 * All fields are held as plain values so the form can build a [CreateAgentRoleRequest] or
 * [UpdateAgentRoleRequest] on save. The role's LLM configuration is expressed by [modelPresetId]
 * alone: [AgentRoleDto.modelId] and [AgentRoleDto.modelSettingsId] are *derived*, read-only values
 * the server resolves from the referenced preset, so they are never part of the draft and the form
 * offers no direct model/settings pickers.
 *
 * [modelPresetId] is optional (U-36/RQ-2): a preset-less draft is valid and savable, and the
 * resulting role is merely non-sendable until a preset is attached. [validate] therefore checks the
 * name only; whether the selected preset is usable is surfaced as an inline hint through
 * [resolveAgentRoleSendability], while the server keeps rejecting an unusable configuration at turn
 * time with a model-configuration error.
 *
 * @property mode Whether this draft creates a new role or edits an existing one.
 * @property roleId Existing role id while editing, used to exclude self from the target selector.
 * @property name Unique (per user) machine-readable role name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role's purpose.
 * @property modelPresetId Identifier of the model preset holding the role's model and settings
 *            profile, or null for a preset-less draft. A preset-less role is legal but non-sendable.
 * @property toolIds Set of tool-definition identifiers attached to the role.
 * @property spawnableAgentRoleIds Unordered role ids this role may spawn; may include the role's own id.
 *            Only roles sharing the role's project scope ([projectId]) are selectable (the server
 *            enforces same-project spawns on save).
 * @property projectId Single user-owned project id the role belongs to, or null for an
 *            **unassociated** role. A role belongs to at most one project; the id is a full
 *            replacement on save (mirroring [toolIds]). Unassociated roles are offered by the
 *            session role selector only while the session has no project selected.
 * @property instructions Ordered instruction list ([AgentInstructionDto] flat entries). A
 *            `spawnable_agents` entry can be placed anywhere and reordered like any other instruction;
 *            only its `message` is read-only (the server generates it from the selected spawn targets).
 *            `model_specific` entries are multi-instance (one per target model) and carry their own
 *            `modelId`.
 * @property errorMessage Optional validation error surfaced to the form.
 */
data class AgentRoleFormState(
    val mode: FormMode = FormMode.NEW,
    val roleId: Long? = null,
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val modelPresetId: Long? = null,
    val toolIds: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val projectId: Long? = null,
    val instructions: List<AgentInstructionDto> = emptyList(),
    val errorMessage: String? = null
) {

    /**
     * Copies this draft with an updated error message, preserving all other fields.
     */
    fun withError(errorMessage: String?): AgentRoleFormState = copy(errorMessage = errorMessage)

    /**
     * Returns a copy of this draft with [projectId] replaced and any previously selected spawn target
     * that no longer shares the new scope dropped.
     *
     * Spawn targets must share the role's project scope (the server rejects a target whose single
     * project differs from the role's on save). When the user switches the role's project in the
     * form, targets of the old scope drop out of the chip row but would otherwise stay in
     * [spawnableAgentRoleIds] and fail with a same-project rejection on save; this helper keeps only
     * the targets that remain legal under the new scope. The role being edited itself always stays
     * eligible (self-spawn is same-scope by definition after the save).
     *
     * @param projectId The new single project scope, or null for an unassociated role.
     * @param roles Same-user roles available as spawn targets, used to resolve each target's scope.
     * @return The updated draft with the new project scope and a pruned spawn-target set.
     */
    fun withProjectScope(projectId: Long?, roles: List<AgentRoleDto>): AgentRoleFormState {
        val keptSpawnTargets = spawnableAgentRoleIds.filter { targetId ->
            targetId == roleId || roles.any { it.id == targetId && it.projectId == projectId }
        }.toSet()
        return copy(projectId = projectId, spawnableAgentRoleIds = keptSpawnTargets)
    }

    /**
     * Validates the required fields. Only the name is mandatory: the model preset is optional
     * (U-36), so a preset-less draft is valid and must not be blocked here. An unusable preset is
     * reported as an inline hint by [resolveAgentRoleSendability] rather than as a validation
     * failure, and the server remains authoritative on save.
     *
     * @return A human-readable validation message, or null when the draft is valid.
     */
    fun validate(): String? {
        if (name.isBlank()) return "Role name cannot be empty."
        return null
    }

    /**
     * Builds a [CreateAgentRoleRequest] from this draft. Only valid when [mode] is NEW and
     * [validate] returns null.
     *
     * The request carries [modelPresetId] and nothing else configuration-related: the removed
     * `modelId`/`modelSettingsId` inputs must never be sent back (U-28/U-29). A null preset id is a
     * legitimate payload (the role is then non-sendable until a preset is attached).
     */
    fun toCreateRequest(): CreateAgentRoleRequest = CreateAgentRoleRequest(
        name = name.trim(),
        displayName = displayName.trim().takeIf { it.isNotBlank() },
        description = description.trim(),
        modelPresetId = modelPresetId,
        toolIds = toolIds,
        spawnableAgentRoleIds = spawnableAgentRoleIds,
        projectId = projectId,
        instructions = instructions
    )

    /**
     * Builds a [UpdateAgentRoleRequest] from this draft. Only valid when [mode] is EDIT and
     * [validate] returns null.
     *
     * Like [toCreateRequest], the payload carries only [modelPresetId] as configuration; a null id
     * detaches the preset and leaves the role non-sendable.
     */
    fun toUpdateRequest(): UpdateAgentRoleRequest = UpdateAgentRoleRequest(
        name = name.trim(),
        displayName = displayName.trim().takeIf { it.isNotBlank() },
        description = description.trim(),
        modelPresetId = modelPresetId,
        toolIds = toolIds,
        spawnableAgentRoleIds = spawnableAgentRoleIds,
        projectId = projectId,
        instructions = instructions
    )
}

/**
 * Conventional default label for a well-known instruction type.
 *
 * Used when pre-seeding a new role and when the user switches a row's type in the form: selecting a
 * type re-labels the row with this name so it stays recognizably named.
 *
 * @param type The [AgentInstructionTypes] key.
 * @return The conventional default name, or [type] itself for unknown/custom type keys.
 */
fun defaultInstructionName(type: String): String = when (type) {
    AgentInstructionTypes.ROLE -> "Role"
    AgentInstructionTypes.MAIN -> "Main instruction"
    AgentInstructionTypes.CUSTOM -> "Custom instruction"
    AgentInstructionTypes.SPAWNABLE_AGENTS -> "Available agents"
    AgentInstructionTypes.MODEL_SPECIFIC -> "Model-specific instruction"
    else -> type
}

/**
 * Creates an empty draft for a new role, pre-seeding one row per well-known instruction type so the
 * user sees the full expected starting shape: `role`, `main`, `spawnable_agents` and `custom`, in
 * that order. The label for `spawnable_agents` rows' message stays read-only (server-resolved) and
 * the others are ready for the user to fill in. `model_specific` is intentionally not pre-seeded:
 * it is a multi-instance, opt-in kind added by switching a row's type in the form.
 *
 * @return A new [AgentRoleFormState] in NEW mode.
 */
fun createEmptyAgentRoleForm(): AgentRoleFormState = AgentRoleFormState(
    mode = FormMode.NEW,
    instructions = listOf(
        AgentInstructionDto(
            type = AgentInstructionTypes.ROLE,
            name = defaultInstructionName(AgentInstructionTypes.ROLE),
            message = ""
        ),
        AgentInstructionDto(
            type = AgentInstructionTypes.MAIN,
            name = defaultInstructionName(AgentInstructionTypes.MAIN),
            message = ""
        ),
        AgentInstructionDto(
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = defaultInstructionName(AgentInstructionTypes.SPAWNABLE_AGENTS),
            message = ""
        ),
        AgentInstructionDto(
            type = AgentInstructionTypes.CUSTOM,
            name = defaultInstructionName(AgentInstructionTypes.CUSTOM),
            message = ""
        )
    )
)

/**
 * Creates an edit draft from an existing role, preserving its resolved instructions (including any
 * read-only `spawnable_agents` entry returned by the server).
 *
 * @receiver The role to edit.
 * @return An [AgentRoleFormState] in EDIT mode pre-filled from the role.
 */
fun AgentRoleDto.toEditFormState(): AgentRoleFormState = AgentRoleFormState(
    mode = FormMode.EDIT,
    roleId = id,
    name = name,
    displayName = displayName ?: "",
    description = description,
    // The preset is the only configuration reference carried into the draft; the DTO's derived
    // model/settings ids are read-only server output and must not become role inputs again.
    modelPresetId = modelPresetId,
    toolIds = tools,
    spawnableAgentRoleIds = spawnableAgentRoleIds,
    projectId = projectId,
    instructions = instructions
)

/**
 * Consolidated dialog state for the Agent Roles management tab.
 */
sealed class AgentRoleDialogState {
    /** No dialog is currently visible. */
    object None : AgentRoleDialogState()

    /** Add-role form dialog. */
    data class AddRole(
        val formState: AgentRoleFormState
    ) : AgentRoleDialogState()

    /** Edit-role form dialog. */
    data class EditRole(
        val role: AgentRoleDto,
        val formState: AgentRoleFormState
    ) : AgentRoleDialogState()

    /** Delete-role confirmation dialog. */
    data class DeleteRole(
        val role: AgentRoleDto
    ) : AgentRoleDialogState()
}
