package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * Immutable snapshot the `spawnable_agents` section is rendered from.
 *
 * Keeping the current role's own project next to the targets lets the intro name the project the
 * model is delegating *from*, while each row names the target's project — the two together are what
 * make same-named targets and cross-project targets distinguishable.
 *
 * @property currentProjectId Project of the role owning the instruction, or null when that role is
 *            unassociated.
 * @property currentProjectName Name of that project, or null when the owning role is unassociated or
 *            the id no longer resolves.
 * @property targets Spawn targets in the order they must be rendered; the loader owns that ordering.
 */
data class SpawnableAgentsAdvertisement(
    val currentProjectId: Long?,
    val currentProjectName: String?,
    val targets: List<AgentRoleSummary>
)

/**
 * Dynamic instruction that advertises the current role's spawn allow-list.
 *
 * The loader is intentionally suspended and scoped by the owning service. Messages are cached per
 * domain instance, which avoids duplicate queries while a role is mapped to both a DTO and a turn.
 * Input text is never accepted because the message is generated exclusively from the advertisement.
 * A role without the `spawn_agent` tool gets an empty message (the composer then drops the blank
 * section) and pays no advertisement read at all.
 *
 * @property name Human-readable marker label; used as the markdown section heading.
 * @property advertisementLoader Resolver for the current, ownership-filtered advertisement.
 * @property spawnAgentToolAvailableLoader Resolver indicating whether this role currently has the
 *            server operator tool enabled.
 */
data class SpawnableAgentsInstruction(
    override val name: String,
    private val advertisementLoader: suspend () -> SpawnableAgentsAdvertisement,
    private val spawnAgentToolAvailableLoader: suspend () -> Boolean = { true }
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.SPAWNABLE_AGENTS

    /** Cached generated text; null means the advertisement has not been loaded yet. */
    private var _message: String? = null

    override val message: String get() = _message ?: ""

    override suspend fun loadMessage() {
        if (_message != null) return
        // Availability is resolved before the advertisement is loaded, so a role that omits the
        // section pays neither the target read nor the batched project read.
        if (!spawnAgentToolAvailableLoader()) {
            _message = ""
            return
        }
        _message = buildMessage(advertisementLoader())
    }

    /**
     * Formats the advertisement as a self-contained markdown section: heading, intro, table and
     * footer, joined by blank lines so it reads naturally when the `DefaultSystemPromptComposer`
     * joins multiple instruction sections into one system message.
     *
     * The table is the addressing surface: the model reads a row's id and passes it in
     * `agent_role_id`, so the `Role id` column comes first and the intro closes by stating the project
     * the owning role itself belongs to.
     *
     * @param advertisement Current snapshot (own project plus the ordered targets).
     * @return Markdown guidance text.
     */
    private fun buildMessage(advertisement: SpawnableAgentsAdvertisement): String {
        val heading = "## " + name.trim().replace('\n', ' ').ifBlank { DEFAULT_HEADING }
        val intro = buildIntro(advertisement)
        val body = if (advertisement.targets.isEmpty()) {
            "No agent roles are available for you to spawn."
        } else {
            renderTable(advertisement.targets)
        }
        val footer = "Do not invent role ids or attempt to spawn roles that are not listed."
        return listOf(heading, intro, body, footer).joinToString("\n\n")
    }

    /**
     * Builds the intro paragraph: the delegation capability, the instructions for passing a target id,
     * the constraint on which roles may be spawned, and finally the project the model delegates from.
     *
     * An empty allow-list drops every sentence that refers to a following table or to listed roles,
     * because neither has a referent there; that case is explained by the body sentence instead.
     *
     * @param advertisement Snapshot carrying the owning role's project and the ordered targets.
     * @return The intro sentences joined by single spaces.
     */
    private fun buildIntro(advertisement: SpawnableAgentsAdvertisement): String {
        val sentences = mutableListOf("You may delegate work with the `spawn_agent` tool.")
        if (advertisement.targets.isNotEmpty()) {
            // Both sentences point at the table only once, through the single "listed below".
            sentences += "Pass the role id in `agent_role_id`, provide a concise subject, and put the " +
                "complete task in `prompt`."
            sentences += "You may only spawn the roles listed below, and they may belong to a project " +
                "other than the current one."
        }
        sentences += currentProjectSentence(advertisement)
        return sentences.joinToString(" ")
    }

    /**
     * Renders the targets as a markdown table with an explicit header row and separator, in the
     * loader-provided order. The first column carries the role id — the value the model passes in
     * `agent_role_id` — so it is the column the intro points at.
     *
     * @param targets Ordered targets to render.
     * @return The complete table (header, separator, one row per target).
     */
    private fun renderTable(targets: List<AgentRoleSummary>): String {
        val header = listOf("| Role id | Name | Project | Description |", "| --- | --- | --- | --- |")
        val rows = targets.map { target ->
            "| ${target.id} | ${cell(target.displayLabel)} | ${projectCell(target)} | " +
                "${cell(target.description)} |"
        }
        return (header + rows).joinToString("\n")
    }

    /**
     * Formats a target's project column through the shared [projectLabel] formatter and the cell
     * sanitiser, so a target's project reads exactly like the current project in the intro.
     *
     * @param target The target whose project is rendered.
     * @return The sanitised project column value.
     */
    private fun projectCell(target: AgentRoleSummary): String =
        cell(projectLabel(target.projectId, target.projectName))

    /**
     * States the project of the role that owns this instruction, so the model knows which project it
     * is delegating from; the sentence is phrased around the project rather than around the role,
     * because the model reads it as its own role and could otherwise mistake it for one of the listed
     * targets.
     *
     * @param advertisement Snapshot carrying the owning role's project.
     * @return One sentence beginning with `The current project is` and ending with a period.
     */
    private fun currentProjectSentence(advertisement: SpawnableAgentsAdvertisement): String =
        "The current project is " +
            projectLabel(advertisement.currentProjectId, advertisement.currentProjectName) + "."

    /**
     * Formats the one project label shared by the table's Project column and the intro's
     * current-project sentence, so an unassociated role is described identically on either side of the
     * delegation and the two can never drift apart.
     *
     * A project id whose name does not resolve is labelled by id alone, so the sentence that embeds
     * this label does not repeat the word "project" in both its stem and the label itself.
     *
     * @param projectId Project the role belongs to, or null when it belongs to no project.
     * @param projectName Resolved project name, or null when the id does not resolve.
     * @return A single-line label; callers rendering it inside a table cell still sanitise it with
     *         [cell].
     */
    private fun projectLabel(projectId: Long?, projectName: String?): String = when {
        projectId == null -> UNASSOCIATED_PROJECT_LABEL
        projectName != null -> "${collapseWhitespace(projectName)} (id $projectId)"
        else -> "#$projectId (name unavailable)"
    }

    /**
     * Normalizes a value for a table cell: whitespace runs collapse to single spaces, backslashes and
     * the column delimiter are escaped so adversarial text cannot break the table, and a blank value
     * renders as [EMPTY_CELL].
     *
     * @param raw The authored value.
     * @return A single-line cell value.
     */
    private fun cell(raw: String): String {
        // The backslash is escaped before the delimiter: a literal backslash directly in front of a
        // `|` would otherwise escape the delimiter's own escape and split the row into cells.
        val normalized = collapseWhitespace(raw)
            .replace("\\", "\\\\")
            .replace("|", "\\|")
        return normalized.ifBlank { EMPTY_CELL }
    }

    /**
     * Collapses every whitespace run (including newlines and tabs) into a single space and trims the
     * result, so any authored text stays on one line.
     *
     * @param raw The authored value.
     * @return The single-line value.
     */
    private fun collapseWhitespace(raw: String): String = raw.trim().replace(WHITESPACE_RUN, " ")

    private companion object {
        /**
         * Heading used when the instruction carries no name. It matches the conventional label the role
         * form pre-fills for this instruction kind, so a blank name and a pre-filled one render the same
         * heading; the spawn scope is stated by the intro sentence instead of by the heading.
         */
        const val DEFAULT_HEADING = "Available agents"

        /** Placeholder rendered for an empty cell. */
        const val EMPTY_CELL = "—"

        /** Shared label for a role that belongs to no project, on either side of the delegation. */
        const val UNASSOCIATED_PROJECT_LABEL = "none"

        /** Any whitespace run, collapsed to a single space in rendered text. */
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
