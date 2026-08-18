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
 * [UpdateAgentRoleRequest] on save. [modelId] and [modelSettingsId] are nullable while editing
 * because a role can lose its references via `ON DELETE SET NULL`; the save action validates that
 * both are present (they are mandatory at creation and on a normal edit).
 *
 * @property mode Whether this draft creates a new role or edits an existing one.
 * @property roleId Existing role id while editing, used to exclude self from the target selector.
 * @property name Unique (per user) machine-readable role name.
 * @property displayName Optional human-friendly display name.
 * @property description Free-form description of the role's purpose.
 * @property modelId Identifier of the LLM model the role uses, or null when not (yet) chosen.
 * @property modelSettingsId Identifier of the chat-capable settings profile the role uses, or null.
 * @property toolIds Set of tool-definition identifiers attached to the role.
 * @property spawnableAgentRoleIds Unordered role ids this role may spawn; may include the role's own id.
 * @property instructions Ordered, type-tagged instruction list. A `model_settings` entry can be placed
 *            anywhere and reordered like any other instruction; only its `message` is read-only (the
 *            server binds it to the role's own settings id and re-resolves it on every read), so the
 *            client never edits its content.
 * @property errorMessage Optional validation error surfaced to the form.
 */
data class AgentRoleFormState(
    val mode: FormMode = FormMode.NEW,
    val roleId: Long? = null,
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val modelId: Long? = null,
    val modelSettingsId: Long? = null,
    val toolIds: Set<Long> = emptySet(),
    val spawnableAgentRoleIds: Set<Long> = emptySet(),
    val instructions: List<AgentInstructionDto> = emptyList(),
    val errorMessage: String? = null
) {

    /**
     * Copies this draft with an updated error message, preserving all other fields.
     */
    fun withError(errorMessage: String?): AgentRoleFormState = copy(errorMessage = errorMessage)

    /**
     * Validates the required fields. The role needs a name and a resolvable model/settings pair;
     * tool ids and instructions are optional.
     *
     * @return A human-readable validation message, or null when the draft is valid.
     */
    fun validate(): String? {
        if (name.isBlank()) return "Role name cannot be empty."
        if (modelId == null) return "A model must be selected."
        if (modelSettingsId == null) return "A settings profile must be selected for the model."
        return null
    }

    /**
     * Builds a [CreateAgentRoleRequest] from this draft. Only valid when [mode] is NEW and
     * [validate] returns null.
     *
     * @throws IllegalStateException when invoked on a draft without model/settings ids.
     */
    fun toCreateRequest(): CreateAgentRoleRequest {
        val modelId = modelId ?: throw IllegalStateException("Cannot create role without a model")
        val modelSettingsId = modelSettingsId
            ?: throw IllegalStateException("Cannot create role without settings")
        return CreateAgentRoleRequest(
            name = name.trim(),
            displayName = displayName.trim().takeIf { it.isNotBlank() },
            description = description.trim(),
            modelId = modelId,
            modelSettingsId = modelSettingsId,
            toolIds = toolIds,
            spawnableAgentRoleIds = spawnableAgentRoleIds,
            instructions = instructions
        )
    }

    /**
     * Builds a [UpdateAgentRoleRequest] from this draft. Only valid when [mode] is EDIT and
     * [validate] returns null.
     *
     * @throws IllegalStateException when invoked on a draft without model/settings ids.
     */
    fun toUpdateRequest(): UpdateAgentRoleRequest {
        val modelId = modelId ?: throw IllegalStateException("Cannot update role without a model")
        val modelSettingsId = modelSettingsId
            ?: throw IllegalStateException("Cannot update role without settings")
        return UpdateAgentRoleRequest(
            name = name.trim(),
            displayName = displayName.trim().takeIf { it.isNotBlank() },
            description = description.trim(),
            modelId = modelId,
            modelSettingsId = modelSettingsId,
            toolIds = toolIds,
            spawnableAgentRoleIds = spawnableAgentRoleIds,
            instructions = instructions
        )
    }
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
    AgentInstructionTypes.MODEL_SETTINGS -> "Model instruction"
    AgentInstructionTypes.CUSTOM -> "Custom instruction"
    AgentInstructionTypes.SPAWNABLE_AGENTS -> "Available agents"
    else -> type
}

/**
 * Creates an empty draft for a new role, pre-seeding one row per well-known instruction type so the
 * user sees the full expected starting shape: `role`, `main`, `model_settings` and `custom`, in that
 * order. The labels are the conventional defaults; the `model_settings` row's message stays read-only
 * (server-resolved) and the others are ready for the user to fill in.
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
            type = AgentInstructionTypes.MODEL_SETTINGS,
            name = defaultInstructionName(AgentInstructionTypes.MODEL_SETTINGS),
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
 * read-only `model_settings` entry returned by the server).
 *
 * @param role The role to edit.
 * @return An [AgentRoleFormState] in EDIT mode pre-filled from [role].
 */
fun AgentRoleDto.toEditFormState(): AgentRoleFormState = AgentRoleFormState(
    mode = FormMode.EDIT,
    roleId = id,
    name = name,
    displayName = displayName ?: "",
    description = description,
    modelId = modelId,
    modelSettingsId = modelSettingsId,
    toolIds = tools,
    spawnableAgentRoleIds = spawnableAgentRoleIds,
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
