package eu.torvian.chatbot.server.service.core.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes

/**
 * One advertised credential profile.
 *
 * Only non-secret metadata crosses this boundary: the name the model passes to `attach credential`,
 * a description and the delivery channel. The secret reference, the value and the fixed command line
 * are never rendered, so a prompt that leaks cannot disclose what the profile holds.
 *
 * @property name Profile name the model passes in `attach credential`.
 * @property description Operator-provided description, or a generated command summary.
 * @property channel Delivery channel, shown so the model knows whether prompts are automatic.
 */
data class CredentialProfileSummary(
    val name: String,
    val description: String,
    val channel: String,
)

/**
 * The credential profiles of one worker, grouped so a name is never ambiguous across workers.
 *
 * @property workerId Worker the profiles are bound to.
 * @property workerName Display name of that worker, or a generated label when the row cannot name it.
 * @property profiles Enabled profiles bound to the worker, in the order they must be rendered.
 */
data class CredentialProfilesGroup(
    val workerId: Long,
    val workerName: String,
    val profiles: List<CredentialProfileSummary>,
)

/**
 * Snapshot the `credential_profiles` section is rendered from.
 *
 * An empty list of groups means the role has no `terminal_session` tool, and the composer then drops
 * the whole section.
 *
 * @property groups Workers whose profiles may be attached, in the order they must be rendered.
 */
data class CredentialProfilesAdvertisement(
    val groups: List<CredentialProfilesGroup>,
)

/**
 * Dynamic instruction that advertises the credential profiles a role may attach.
 *
 * The loader is suspended and scoped by the owning service, and the message is cached per domain
 * instance so mapping one role to both a DTO and a turn reads the advertisement once. Input text is
 * never accepted: the section is generated exclusively from current profile metadata.
 *
 * @property id Identifier of the stored instruction row the value was read from.
 * @property name Human-readable marker label; used as the markdown section heading.
 * @property advertisementLoader Resolver for the profiles reachable from this role's worker(s).
 * @property terminalSessionToolAvailableLoader Resolver indicating whether this role currently has the
 *   `terminal_session` tool enabled.
 * @property linkedRoleIds Ids of the agent roles that link that row.
 */
data class CredentialProfilesInstruction(
    override val id: Long,
    override val name: String,
    private val advertisementLoader: suspend () -> CredentialProfilesAdvertisement,
    private val terminalSessionToolAvailableLoader: suspend () -> Boolean = { true },
    override val linkedRoleIds: Set<Long> = emptySet()
) : AgentInstruction {
    override val type: String = AgentInstructionTypes.CREDENTIAL_PROFILES

    /** Cached generated text; null means the advertisement has not been loaded yet. */
    private var _message: String? = null

    override val message: String get() = _message ?: ""

    override suspend fun loadMessage() {
        if (_message != null) return
        // Availability is resolved first, so a role without the tool pays no profile read at all.
        if (!terminalSessionToolAvailableLoader()) {
            _message = ""
            return
        }
        _message = buildMessage(advertisementLoader())
    }

    /**
     * Formats the advertisement as a self-contained markdown section: heading, intro, one table per
     * worker and a footer, joined by blank lines.
     *
     * @param advertisement Current snapshot of the reachable profiles.
     * @return Markdown guidance text, or an empty string when the role has no worker to attach on.
     */
    private fun buildMessage(advertisement: CredentialProfilesAdvertisement): String {
        val groups = advertisement.groups.filter { it.profiles.isNotEmpty() }
        if (groups.isEmpty()) return ""

        val heading = "## " + name.trim().replace('\n', ' ').ifBlank { DEFAULT_HEADING }
        val intro = buildIntro()
        val body = groups.joinToString("\n\n") { group -> renderGroup(group, showWorker = groups.size > 1) }
        val footer = "Do not invent profile names: a name that is not listed here cannot be attached."
        return listOf(heading, intro, body, footer).joinToString("\n\n")
    }

    /**
     * @return The intro paragraph stating what attaching does and what the model cannot change.
     */
    private fun buildIntro(): String = listOf(
        "You may attach one of these credential profiles to a `terminal_session` with " +
            "`attach` and `credential=<name>`.",
        "The profile's command, arguments and delivery channel are fixed by an operator and cannot be " +
            "changed, and the secret itself is never returned.",
        "While a profile is attached the session's input and output belong to it, so shell composition, " +
            "`$?` and job control do not apply around it.",
        "The profile detaches automatically when its process exits, and the session then returns to its " +
            "shell."
    ).joinToString(" ")

    /**
     * Renders one worker's profiles as a table.
     *
     * @param group Worker and its profiles.
     * @param showWorker Whether the worker name must be stated above the table, which is the case only
     *   when more than one worker contributes rows.
     * @return The optional worker heading followed by the table.
     */
    private fun renderGroup(group: CredentialProfilesGroup, showWorker: Boolean): String {
        val header = listOf("| Profile | Channel | Description |", "| --- | --- | --- |")
        val rows = group.profiles.map { profile ->
            "| ${cell(profile.name)} | ${cell(profile.channel)} | ${cell(profile.description)} |"
        }
        val table = (header + rows).joinToString("\n")
        return if (showWorker) "### ${cell(group.workerName)}\n\n$table" else table
    }

    /**
     * Normalizes a value for a table cell: whitespace runs collapse to single spaces, backslashes and
     * the column delimiter are escaped so authored text cannot break the table, and a blank value
     * renders as [EMPTY_CELL].
     *
     * @param raw The authored value.
     * @return A single-line cell value.
     */
    private fun cell(raw: String): String {
        // The backslash is escaped before the delimiter: a literal backslash directly in front of a
        // `|` would otherwise escape the delimiter's own escape and split the row into cells.
        val normalized = raw.trim().replace(WHITESPACE_RUN, " ")
            .replace("\\", "\\\\")
            .replace("|", "\\|")
        return normalized.ifBlank { EMPTY_CELL }
    }

    private companion object {
        /** Heading used when the instruction carries no name. */
        const val DEFAULT_HEADING = "Available credential profiles"

        /** Placeholder rendered for an empty cell. */
        const val EMPTY_CELL = "—"

        /** Any whitespace run, collapsed to a single space in rendered text. */
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
