package eu.torvian.chatbot.server.service.core.agent

/**
 * Composes the final system prompt for an agent role from its resolved domain instructions.
 *
 * Centralizing composition here isolates the concatenation/formatting logic so future dynamic
 * instruction kinds (spawnable agents, skills) become a single-point change, and keeps the
 * conversation-turn preparation layer free of prompt-formatting concerns.
 */
interface SystemPromptComposer {

    /**
     * Produces the system prompt string for the given instructions.
     *
     * Every instruction's message is resolved (via [AgentInstruction.loadMessage]) before being joined,
     * so DB-backed instructions (e.g. [ModelSettingsInstruction]) reflect the latest settings text.
     * Blank messages are dropped; the remaining messages are joined with `"\n---\n"`. When the list is
     * empty (or all messages are blank) the returned string is empty, which signals downstream layers to
     * omit the system message entirely.
     *
     * @param instructions The agent role's domain instructions, in order.
     * @return The composed system prompt, or an empty string when there is nothing to send.
     */
    suspend fun compose(instructions: List<AgentInstruction>): String
}
