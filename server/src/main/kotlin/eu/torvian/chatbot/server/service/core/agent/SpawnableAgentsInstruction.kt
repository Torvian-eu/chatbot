package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Dynamic instruction that advertises the current role's spawn allow-list.
 *
 * The loader is intentionally suspended and scoped by the owning service. Messages are cached per
 * domain instance, which avoids duplicate queries while a role is mapped to both a DTO and a turn.
 * Input text is never accepted because the message is generated exclusively from role summaries.
 *
 * @property name Human-readable marker label; used as the markdown section heading.
 * @property roleSummaryLoader Resolver for current, ownership-filtered target summaries.
 * @property spawnAgentToolAvailableLoader Resolver indicating whether this role currently has the
 *            server operator tool enabled.
 */
data class SpawnableAgentsInstruction(
    override val name: String,
    private val roleSummaryLoader: suspend () -> List<AgentRoleSummary>,
    private val spawnAgentToolAvailableLoader: suspend () -> Boolean = { true }
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.SPAWNABLE_AGENTS

    /** Cached generated text; null means the role summaries have not been loaded yet. */
    private var _message: String? = null

    override val message: String get() = _message ?: ""

    override suspend fun loadMessage() {
        if (_message != null) return
        val summaries = roleSummaryLoader()
        val toolAvailable = spawnAgentToolAvailableLoader()
        _message = buildMessage(summaries, toolAvailable)
    }

    /**
     * Formats deterministic prompt text from the permitted role summaries as a self-contained markdown
     * section.
     *
     * The section opens with the instruction's [name] as a level-2 heading and is composed entirely of
     * markdown (paragraph text and a bullet list), so it reads naturally when the
     * [DefaultSystemPromptComposer] joins multiple instruction sections into one system message. When
     * the `spawn_agent` tool is not enabled the section is intentionally omitted from the system
     * prompt: returning an empty string makes the composer drop the blank message.
     *
     * @param summaries Current target summaries in deterministic (name-sorted) order.
     * @param toolAvailable Whether the current role has the operator tool enabled.
     * @return Markdown guidance text, or an empty string when the tool is not enabled.
     */
    private fun buildMessage(summaries: List<AgentRoleSummary>, toolAvailable: Boolean): String {
        if (!toolAvailable) return ""
        val heading = "## " + name.trim().replace('\n', ' ').ifBlank { "Available agents" }
        val intro = "You may delegate work with the `spawn_agent` tool. " +
            "Use the exact role name in `agent_role_name`, provide a concise subject, and put the " +
            "complete task in `prompt`. You may only spawn the roles listed below."
        val entries = if (summaries.isEmpty()) {
            "- (No agent roles are currently available.)"
        } else {
            summaries.joinToString("\n") { summary ->
                val roleName = summary.name.trim()
                val description = summary.description.trim().replace('\n', ' ')
                val descriptionPart = if (description.isBlank()) "" else ": $description"
                "- `$roleName`$descriptionPart"
            }
        }
        val footer = "Do not invent role names or attempt to spawn other roles."
        return listOf(heading, intro, entries, footer).joinToString("\n\n")
    }
}