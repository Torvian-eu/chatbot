package eu.torvian.chatbot.server.service.core.impl

import arrow.core.raise.Raise
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.modelIdOrNull
import eu.torvian.chatbot.server.data.entities.InstructionEntity
import kotlinx.serialization.json.Json

/**
 * Shared per-role instruction-list rules: the singleton-kind limits and the `model_specific`
 * constraints applied to any surface that writes a role's ordered instruction list.
 *
 * Kept in one place so every write path (role create/update and later flows that mutate the list)
 * rejects the same shapes with identical wording. The rules judge the effective list — rule inputs
 * come from referenced rows or from authored inline content — and raise through the caller's error
 * surface via the supplied factory.
 */
internal object AgentRoleInstructionRules {

    /**
     * One linked instruction as the per-role rules see it.
     *
     * @property type The linked row's instruction kind.
     * @property modelId The model a `model_specific` row targets, or null when the row does not carry
     *            a usable `modelId`.
     */
    data class RuleInput(
        val type: String,
        val modelId: Long?
    )

    /**
     * Validates the per-role instruction-list rules for one effective instruction list.
     *
     * Enforces: at most one `role`, one `main` and one `spawnable_agents` row; every `model_specific`
     * row carries a `modelId`; and `model_specific` rows target distinct models. Content-level
     * duplicates of any kind are legal and unchecked here.
     *
     * @param instructions The referenced rows in the role's order.
     * @param raise The raise scope of the caller.
     * @param instructionValidationFailed Factory building the caller's validation-failure error.
     */
    fun <E> validate(
        instructions: List<RuleInput>,
        raise: Raise<E>,
        instructionValidationFailed: (reason: String) -> E
    ) = with(raise) {
        violationReason(instructions)?.let { reason -> raise(instructionValidationFailed(reason)) }
    }

    /**
     * Returns the first violated per-role rule for one effective instruction list, or null when it is
     * valid.
     *
     * Exposed separately from [validate] so a caller that has to judge several lists (for example the
     * lists of every role linking a shared row) can collect the failure instead of raising immediately.
     *
     * @param instructions The referenced rows in the role's order.
     * @return The violated rule's wording, or null when the list is valid.
     */
    fun violationReason(instructions: List<RuleInput>): String? {
        val roleCount = instructions.count { it.type == AgentInstructionTypes.ROLE }
        val mainCount = instructions.count { it.type == AgentInstructionTypes.MAIN }
        val spawnableInstructionCount = instructions.count { it.type == AgentInstructionTypes.SPAWNABLE_AGENTS }
        if (roleCount > 1) return "At most one 'role' instruction is allowed"
        if (mainCount > 1) return "At most one 'main' instruction is allowed"
        if (spawnableInstructionCount > 1) return "At most one 'spawnable_agents' instruction is allowed"

        // A `model_specific` instruction is meaningless without its target model: the composer keeps
        // only the instance matching the active model, so a missing target would be silently dropped
        // at read time. Reject it up front instead of accepting data that disappears.
        if (instructions.any { it.type == AgentInstructionTypes.MODEL_SPECIFIC && it.modelId == null }) {
            return "A 'model_specific' instruction must include custom.modelId"
        }

        // `model_specific` is multi-instance (one per target model) but each instance must reference a
        // distinct model: two entries for the same model would be redundant and ambiguous at compose
        // time, where the composer keeps only the matching instance.
        val modelSpecificModelIds = instructions
            .filter { it.type == AgentInstructionTypes.MODEL_SPECIFIC }
            .mapNotNull { it.modelId }
        if (modelSpecificModelIds.distinct().size != modelSpecificModelIds.size) {
            return "Each 'model_specific' instruction must reference a distinct model"
        }
        return null
    }
}

/**
 * Projects a referenced instruction row into the per-role rule input.
 *
 * The row's stored `custom` text supplies the model target of a `model_specific` row; a malformed or
 * absent value yields null, which the rules then reject with their own wording. Shared by every write
 * path that has to validate an effective list, so all of them classify a row identically.
 *
 * @receiver The referenced instruction row.
 * @param json Codec used to read the stored `custom` text.
 * @return The rule input describing the row's kind and model target.
 */
internal fun InstructionEntity.toRuleInput(json: Json): AgentRoleInstructionRules.RuleInput =
    AgentRoleInstructionRules.RuleInput(
        type = type,
        modelId = parseStoredCustom(json, custom)?.modelIdOrNull()
    )
