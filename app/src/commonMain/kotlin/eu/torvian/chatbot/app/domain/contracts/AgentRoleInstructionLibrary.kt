package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelIdOrNull

/**
 * The instruction kinds offered by the role form, both as a new row's type and as a library row that
 * can be linked.
 *
 * Every well-known, client-editable kind is included. `role`, `main` and `spawnable_agents` are
 * single-instance (mirroring the server's per-role rules); `custom` and `model_specific` may appear
 * more than once, with each `model_specific` row targeting its own model.
 */
val editableInstructionTypes: List<String> = listOf(
    AgentInstructionTypes.ROLE,
    AgentInstructionTypes.MAIN,
    AgentInstructionTypes.CUSTOM,
    AgentInstructionTypes.SPAWNABLE_AGENTS,
    AgentInstructionTypes.MODEL_SPECIFIC
)

/**
 * Instruction kinds a role may link at most once, mirroring the server's per-role rules.
 */
private val singleInstanceInstructionTypes = setOf(
    AgentInstructionTypes.ROLE,
    AgentInstructionTypes.MAIN,
    AgentInstructionTypes.SPAWNABLE_AGENTS
)

/**
 * The library rows the role currently being edited may link.
 *
 * The role form links existing rows instead of authoring copies of them, so this is what the picker
 * offers: rows the role does not link yet, of a kind the client can render, and only those that keep
 * the role valid. Excluding the rest keeps the user from assembling a draft the server would reject —
 * a repeated row id, a second single-instance row, or a second `model_specific` row aimed at an
 * already-targeted model. Rows of an unknown kind are dropped for the same reason the form renders
 * them read-only: the client cannot edit or describe them.
 *
 * @param library The user's instruction library, as delivered by the repository.
 * @param instructions The role's instruction drafts, including rows it already links.
 * @return The linkable rows, in the order of [library] (an empty list when none qualify).
 */
fun selectableLibraryInstructions(
    library: List<AgentInstructionDto>,
    instructions: List<AgentRoleInstructionDraft>
): List<AgentInstructionDto> {
    val linkedIds = instructions.mapNotNull { it.id }.toSet()
    val usedTypes = instructions.map { it.type }.toSet()
    val usedModelIds = instructions
        .filter { it.type == AgentInstructionTypes.MODEL_SPECIFIC }
        .mapNotNull { it.modelSpecificId() }
        .toSet()

    return library.filter { row ->
        when {
            // The same row may appear at most once in a role (the server rejects a duplicate link).
            row.id in linkedIds -> false

            row.type !in editableInstructionTypes -> false

            row.type in singleInstanceInstructionTypes -> row.type !in usedTypes

            // A model_specific row without a usable target could never apply to the role, and two of
            // them must not aim at the same model.
            row.type == AgentInstructionTypes.MODEL_SPECIFIC ->
                row.custom.modelIdOrNull()?.let { modelId -> modelId !in usedModelIds } == true

            else -> true
        }
    }
}
