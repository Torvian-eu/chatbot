package eu.torvian.chatbot.server.service.core.agent

/**
 * Default [SystemPromptComposer] that joins resolved instruction messages with a `"\n\n---\n\n"` separator.
 *
 * @property instructionSeparator Separator inserted between consecutive instruction messages.
 */
class DefaultSystemPromptComposer(
    private val instructionSeparator: String = "\n\n---\n\n"
) : SystemPromptComposer {

    override suspend fun compose(role: AgentRole): String {
        val roleModelId = role.modelId
        val messages = role.instructions
            // Model-specific instructions only participate when their target model matches the
            // role's current model; a null roleModelId excludes all of them.
            .filter { it !is ModelSpecificInstruction || it.isActiveFor(roleModelId) }
            .map { instruction ->
                instruction.loadMessage()
                instruction.message
            }
        return messages
            .filter { it.isNotBlank() }
            .joinToString(instructionSeparator)
    }
}
