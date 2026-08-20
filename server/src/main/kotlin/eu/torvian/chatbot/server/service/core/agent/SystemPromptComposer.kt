package eu.torvian.chatbot.server.service.core.agent

/**
 * Composes the final system prompt for an agent role from its resolved domain instructions.
 */
interface SystemPromptComposer {

    /**
     * Produces the composed system prompt for the given [role].
     *
     * @param role The agent role whose instructions should be composed.
     * @return The composed system prompt, or an empty string when there is nothing to send.
     */
    suspend fun compose(role: AgentRole): String
}
