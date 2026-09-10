package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.domain.contracts.AgentRoleSection
import eu.torvian.chatbot.app.domain.contracts.NO_PROJECT_SECTION_TITLE
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlin.test.Test
import kotlin.time.Instant

/**
 * Tests for [AgentRoleListPage]: project-grouped sections render with headers, the project filter
 * narrows the visible roles, and empty scopes show the per-scope message.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRoleListPageTest {

    private fun role(id: Long, name: String, projectId: Long? = null) = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = 1L,
        modelSettingsId = 2L,
        tools = emptySet(),
        instructions = emptyList(),
        projectId = projectId
    )

    private fun project(id: Long, name: String) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = emptySet()
    )

    /**
     * Renders [AgentRoleListPage] inside a compose test and runs [block] against it.
     *
     * @param sections Grouped sections to render.
     * @param projects Projects used for the filter options.
     * @param block Assertions/interactions executed against the composed page.
     */
    private fun setListPage(
        sections: List<AgentRoleSection>,
        projects: List<ProjectDto>,
        block: ComposeUiTest.() -> Unit
    ) = runComposeUiTest {
        setContent {
            AgentRoleListPage(
                sections = sections,
                projects = projects,
                selectedRole = null,
                onRoleSelected = {},
                onToggleRoleDisabled = {},
                onAddNewRole = {}
            )
        }
        block()
    }

    @Test
    fun groupedSections_renderProjectHeadersWithRoles() {
        setListPage(
            sections = listOf(
                AgentRoleSection("Research", 1, listOf(role(1, "writer", projectId = 1))),
                AgentRoleSection("Coding", 2, listOf(role(2, "coder", projectId = 2))),
                AgentRoleSection(NO_PROJECT_SECTION_TITLE, null, listOf(role(3, "loose")))
            ),
            projects = listOf(project(1, "Research"), project(2, "Coding"))
        ) {
            onNodeWithText("Research").assertIsDisplayed()
            onNodeWithText("writer").assertIsDisplayed()
            onNodeWithText("Coding").assertIsDisplayed()
            onNodeWithText("coder").assertIsDisplayed()
            onNodeWithText(NO_PROJECT_SECTION_TITLE).assertIsDisplayed()
            onNodeWithText("loose").assertIsDisplayed()
        }
    }

    @Test
    fun filter_selectingProjectShowsOnlyThatProjectRoles() {
        setListPage(
            sections = listOf(
                AgentRoleSection("Research", 1, listOf(role(1, "writer", projectId = 1))),
                AgentRoleSection("Coding", 2, listOf(role(2, "coder", projectId = 2)))
            ),
            projects = listOf(project(1, "Research"), project(2, "Coding"))
        ) {
            // "Coding" appears twice: the section header and the filter option. Scope the click to
            // the option that lives inside the dropdown menu (rendered last in the node tree).
            onNodeWithText("Filter by project").performClick()
            onAllNodesWithText("Coding").onLast().performClick()

            onNodeWithText("coder").assertIsDisplayed()
            onNodeWithText("writer").assertDoesNotExist()
        }
    }

    @Test
    fun filter_selectingNoProjectShowsOnlyUnassociatedRoles() {
        setListPage(
            sections = listOf(
                AgentRoleSection("Research", 1, listOf(role(1, "writer", projectId = 1))),
                AgentRoleSection(NO_PROJECT_SECTION_TITLE, null, listOf(role(2, "loose")))
            ),
            projects = listOf(project(1, "Research"))
        ) {
            onNodeWithText("Filter by project").performClick()
            onAllNodesWithText(NO_PROJECT_SECTION_TITLE).onLast().performClick()

            onNodeWithText("loose").assertIsDisplayed()
            onNodeWithText("writer").assertDoesNotExist()
        }
    }

    @Test
    fun filter_emptyScope_showsPerScopeMessage() {
        // The filter is saved across recompositions, so once "Research" is selected the scope can
        // become empty reactively (e.g. the last role was moved to another project — AC-5). The page
        // must then show the per-scope message instead of the global no-roles copy.
        val sectionsState = mutableStateOf(
            listOf(
                AgentRoleSection("Research", 1, listOf(role(1, "writer", projectId = 1))),
                AgentRoleSection("Coding", 2, listOf(role(2, "coder", projectId = 2)))
            )
        )
        val projects = listOf(project(1, "Research"), project(2, "Coding"))

        runComposeUiTest {
            setContent {
                AgentRoleListPage(
                    sections = sectionsState.value,
                    projects = projects,
                    selectedRole = null,
                    onRoleSelected = {},
                    onToggleRoleDisabled = {},
                    onAddNewRole = {}
                )
            }

            onNodeWithText("Filter by project").performClick()
            onAllNodesWithText("Research").onLast().performClick()
            onNodeWithText("writer").assertIsDisplayed()

            // Simulate the role moving to another project: the Research section disappears while the
            // saved filter still targets project 1.
            sectionsState.value = listOf(
                AgentRoleSection("Coding", 2, listOf(role(2, "coder", projectId = 2)))
            )
            waitForIdle()

            onNodeWithText("No agent roles in this scope.").assertIsDisplayed()
            onNodeWithText("writer").assertDoesNotExist()
            onNodeWithText("coder").assertDoesNotExist()
        }
    }
}
