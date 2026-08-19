package eu.torvian.chatbot.server.service.core.agent

/**
 * Default [SystemPromptComposer] that joins resolved instruction messages with a `"\n\n---\n\n"` separator.
 *
 * @property instructionSeparator Separator inserted between consecutive instruction messages.
 *            Exposed for tests and future formatting tweaks; defaults to `"\n\n---\n\n"`.
 */
class DefaultSystemPromptComposer(
    private val instructionSeparator: String = "\n\n---\n\n"
) : SystemPromptComposer {

    override suspend fun compose(instructions: List<AgentInstruction>): String {
        val messages = instructions.map { instruction ->
            instruction.loadMessage()
            instruction.message
        }
        return messages
            .filter { it.isNotBlank() }
            .joinToString(instructionSeparator)
    }
}
