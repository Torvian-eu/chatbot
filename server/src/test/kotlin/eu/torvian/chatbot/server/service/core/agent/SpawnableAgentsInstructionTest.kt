package eu.torvian.chatbot.server.service.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the `spawnable_agents` prompt section ([SpawnableAgentsInstruction]): the markdown table
 * shape, cell formatting and sanitisation, the intro's current-project sentence, the shared project label,
 * the empty-allow-list rendering and the omission when `spawn_agent` is not enabled.
 */
class SpawnableAgentsInstructionTest {

    /**
     * Builds a target summary.
     *
     * @param id Value advertised in the id column.
     * @param name Machine-readable name used as the label fallback.
     * @param displayName Preferred label when non-blank.
     * @param description Authored description.
     * @param projectId Project membership, or null when unassociated.
     * @param projectName Resolved project name, or null when the id does not resolve.
     * @return The summary fixture.
     */
    private fun summary(
        id: Long,
        name: String,
        displayName: String? = null,
        description: String = "",
        projectId: Long? = null,
        projectName: String? = null
    ) = AgentRoleSummary(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        projectId = projectId,
        projectName = projectName
    )

    /**
     * Renders the section for [advertisement] through the real caching loader path.
     *
     * @param advertisement Snapshot to render.
     * @param toolAvailable Whether `spawn_agent` is attached to the role.
     * @return The generated section text.
     */
    private suspend fun render(
        advertisement: SpawnableAgentsAdvertisement,
        toolAvailable: Boolean = true
    ): String {
        val instruction = SpawnableAgentsInstruction(
            name = "Available agents",
            advertisementLoader = { advertisement },
            spawnAgentToolAvailableLoader = { toolAvailable }
        )
        instruction.loadMessage()
        return instruction.message
    }

    /**
     * Verifies the table shape: header row, markdown separator and one row per target in the
     * loader-provided order, with the id rendered as a plain number.
     */
    @Test
    fun `renders a markdown table with a header row and one row per target`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = 7L,
            currentProjectName = "Web",
            targets = listOf(
                summary(
                    id = 42L,
                    name = "writer",
                    displayName = "Writer",
                    description = "Writes release summaries",
                    projectId = 7L,
                    projectName = "Web"
                ),
                summary(id = 5L, name = "implementer")
            )
        )

        val message = render(advertisement)

        assertTrue(message.contains("| Role id | Name | Project | Description |"), message)
        assertTrue(message.contains("| --- | --- | --- | --- |"), message)
        assertTrue(message.contains("| 42 | Writer | Web (id 7) | Writes release summaries |"), message)
        // An empty description renders as the placeholder, and an unassociated target uses the same
        // label as the current project.
        assertTrue(message.contains("| 5 | implementer | none | — |"), message)
        // Rows keep the loader-provided order.
        assertTrue(message.indexOf("| 42 |") < message.indexOf("| 5 |"), message)
    }

    /**
     * Verifies the label rule: the display name is used when it carries text, otherwise the machine name.
     */
    @Test
    fun `labels a target with its display name or falls back to the machine name`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = null,
            currentProjectName = null,
            targets = listOf(
                summary(id = 1L, name = "machine-only"),
                summary(id = 2L, name = "machine-two", displayName = "   ")
            )
        )

        val message = render(advertisement)

        assertTrue(message.contains("| 1 | machine-only | none | — |"), message)
        assertTrue(message.contains("| 2 | machine-two | none | — |"), message)
    }

    /**
     * Verifies the name-unavailable project label, which is shared with the intro's current-project
     * sentence and labels the project by id alone.
     */
    @Test
    fun `renders the name-unavailable project label when the target's project does not resolve`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = null,
            currentProjectName = null,
            targets = listOf(summary(id = 3L, name = "orphan", projectId = 9L, projectName = null))
        )

        val message = render(advertisement)

        assertTrue(message.contains("| 3 | orphan | #9 (name unavailable) | — |"), message)
    }

    /**
     * Verifies the intro: it points at `agent_role_id`, constrains which roles may be spawned, closes
     * with the project the instruction's role is delegating from, and refers to the table only once.
     */
    @Test
    fun `intro addresses the id parameter and names the current project`() = runTest {
        val associated = render(
            SpawnableAgentsAdvertisement(
                currentProjectId = 7L,
                currentProjectName = "Web",
                targets = listOf(summary(id = 1L, name = "writer"))
            )
        )
        assertTrue(associated.contains("Pass the role id in `agent_role_id`"), associated)
        assertTrue(
            associated.contains(
                "You may only spawn the roles listed below, and they may belong to a project other " +
                    "than the current one."
            ),
            associated
        )
        assertTrue(associated.contains("The current project is Web (id 7)."), associated)
        assertTrue(
            associated.contains("Do not invent role ids or attempt to spawn roles that are not listed."),
            associated
        )
        // The current project is stated last, after the constraint on the listed roles.
        assertTrue(
            associated.indexOf("The current project is Web (id 7).") >
                associated.indexOf("You may only spawn the roles listed below"),
            associated
        )
        // Only the constraint points at the table, so the intro does not repeat itself.
        assertEquals(1, Regex("below").findAll(associated).count(), associated)
        // The removed name parameter is never advertised.
        assertFalse(associated.contains("agent_role_name"), associated)
    }

    /**
     * Verifies the two current-project fallbacks: an unresolvable project id and an unassociated role.
     */
    @Test
    fun `intro falls back for an unresolvable or missing current project`() = runTest {
        val unresolvable = render(
            SpawnableAgentsAdvertisement(
                currentProjectId = 9L,
                currentProjectName = null,
                targets = listOf(summary(id = 1L, name = "writer"))
            )
        )
        assertTrue(unresolvable.contains("The current project is #9 (name unavailable)."), unresolvable)

        val unassociated = render(
            SpawnableAgentsAdvertisement(
                currentProjectId = null,
                currentProjectName = null,
                targets = listOf(summary(id = 1L, name = "writer"))
            )
        )
        assertTrue(unassociated.contains("The current project is none."), unassociated)
    }

    /**
     * Verifies the empty allow-list body: no table and no promise of one, the body saying instead that
     * nothing is available to spawn, while the intro still states the current project.
     */
    @Test
    fun `renders no table for an empty allow-list`() = runTest {
        val message = render(
            SpawnableAgentsAdvertisement(currentProjectId = 7L, currentProjectName = "Web", targets = emptyList())
        )

        assertTrue(message.contains("No agent roles are available for you to spawn."), message)
        assertTrue(message.contains("The current project is Web (id 7)."), message)
        assertFalse(message.contains("| Role id |"), message)
        // Nothing is listed, so the intro must neither ask for a role id nor refer to a list.
        assertFalse(message.contains("`agent_role_id`"), message)
        assertFalse(message.contains("listed below"), message)
        assertFalse(message.contains("project other than the current one"), message)
    }

    /**
     * Verifies the section heading: the instruction's own name when it carries text, otherwise the
     * built-in default.
     */
    @Test
    fun `falls back to the default heading when the instruction has no name`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = null,
            currentProjectName = null,
            targets = listOf(summary(id = 1L, name = "writer"))
        )

        val unnamed = SpawnableAgentsInstruction(
            name = "   ",
            advertisementLoader = { advertisement },
            spawnAgentToolAvailableLoader = { true }
        )
        unnamed.loadMessage()
        assertEquals("## Available agents", unnamed.message.lines().first())

        val named = SpawnableAgentsInstruction(
            name = "My spawn team",
            advertisementLoader = { advertisement },
            spawnAgentToolAvailableLoader = { true }
        )
        named.loadMessage()
        assertEquals("## My spawn team", named.message.lines().first())
    }

    /**
     * Verifies that the intro's current-project sentence and a target's Project cell are rendered from
     * the same label formatter, so all three project forms read identically in both places.
     */
    @Test
    fun `renders the same project label in the sentence and in a target's cell`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = 7L,
            currentProjectName = "Web",
            targets = listOf(
                summary(id = 1L, name = "named", projectId = 1L, projectName = "Web"),
                summary(id = 2L, name = "unresolvable", projectId = 9L, projectName = null),
                summary(id = 3L, name = "unassociated")
            )
        )

        val message = render(advertisement)

        assertTrue(message.contains("The current project is Web (id 7)."), message)
        assertTrue(message.contains("| 1 | named | Web (id 1) | — |"), message)
        assertTrue(message.contains("| 2 | unresolvable | #9 (name unavailable) | — |"), message)
        assertTrue(message.contains("| 3 | unassociated | none | — |"), message)

        // The sentence uses the same two remaining forms as the cells above.
        val unresolvableCurrent = render(advertisement.copy(currentProjectId = 9L, currentProjectName = null))
        assertTrue(unresolvableCurrent.contains("The current project is #9 (name unavailable)."), unresolvableCurrent)
        val unassociatedCurrent = render(advertisement.copy(currentProjectId = null, currentProjectName = null))
        assertTrue(unassociatedCurrent.contains("The current project is none."), unassociatedCurrent)
    }

    /**
     * Verifies that the whole section is omitted when the role does not have `spawn_agent` enabled.
     */
    @Test
    fun `omits the section when spawn_agent is not enabled`() = runTest {
        val message = render(
            SpawnableAgentsAdvertisement(
                currentProjectId = null,
                currentProjectName = null,
                targets = listOf(summary(id = 1L, name = "writer"))
            ),
            toolAvailable = false
        )

        assertEquals("", message)
    }

    /**
     * Verifies that adversarial content cannot break the table: the column delimiter is escaped, embedded
     * whitespace collapses to single spaces and the row stays on one line.
     */
    @Test
    fun `sanitises table cells`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = 1L,
            currentProjectName = "Web",
            targets = listOf(
                summary(
                    id = 1L,
                    name = "pipe",
                    displayName = "A | B",
                    description = "line one\nline two\twith  spaces",
                    projectId = 1L,
                    projectName = "Web | Corp"
                )
            )
        )

        val message = render(advertisement)

        assertTrue(
            message.contains("| 1 | A \\| B | Web \\| Corp (id 1) | line one line two with spaces |"),
            message
        )
        assertFalse(message.contains("line one\nline two"), message)
    }

    /**
     * Verifies that a backslash in front of the delimiter cannot neutralize the delimiter's escape:
     * the backslash is escaped as well, so the row keeps its cells and renders the authored text back.
     */
    @Test
    fun `escapes backslashes so they cannot unescape the column delimiter`() = runTest {
        val advertisement = SpawnableAgentsAdvertisement(
            currentProjectId = null,
            currentProjectName = null,
            targets = listOf(
                summary(
                    id = 2L,
                    name = "escape",
                    displayName = """a\|b""",
                    description = """C:\docs"""
                )
            )
        )

        val message = render(advertisement)

        // Three backslashes before the pipe: the authored backslash renders as `\` and the pipe as
        // `\|`, so a reader sees the authored `a\|b` while the row keeps four cells.
        assertTrue(message.contains("""| 2 | a\\\|b | none | C:\\docs |"""), message)
        assertEquals(1, message.lines().count { it.startsWith("| 2 |") }, message)
    }

    /**
     * Verifies the omission ordering: when the role does not have `spawn_agent` the advertisement is
     * never resolved, so the omitted section pays for no query at all.
     */
    @Test
    fun `does not load the advertisement when spawn_agent is not enabled`() = runTest {
        var advertisementLoads = 0
        val instruction = SpawnableAgentsInstruction(
            name = "Available agents",
            advertisementLoader = {
                advertisementLoads += 1
                SpawnableAgentsAdvertisement(
                    currentProjectId = null,
                    currentProjectName = null,
                    targets = listOf(summary(id = 1L, name = "writer"))
                )
            },
            spawnAgentToolAvailableLoader = { false }
        )

        instruction.loadMessage()

        assertEquals("", instruction.message)
        assertEquals(0, advertisementLoads)
    }
}
