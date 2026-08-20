package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Per-model instruction that applies only when the role is running on [modelId].
 *
 * Its [message] is static, user-authored text — never blanked during composition. Filtering happens
 * in the [SystemPromptComposer] via [isActiveFor], which compares [modelId] against the role's model.
 *
 * @property name Human-readable label of the instruction.
 * @property message The static instruction text (already populated).
 * @property modelId Identifier of the model this instruction applies to.
 */
data class ModelSpecificInstruction(
    override val name: String,
    override val message: String,
    val modelId: Long
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.MODEL_SPECIFIC

    override suspend fun loadMessage() {
        // Message is already populated.
    }

    /**
     * Whether this instruction should be included when composing the system prompt for a conversation
     * running on [roleModelId].
     *
     * @param roleModelId The model the conversation is currently running on, or null when unknown.
     * @return `true` when the instruction should be included.
     */
    fun isActiveFor(roleModelId: Long?): Boolean = roleModelId == modelId
}
