package eu.torvian.chatbot.app.compose.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.domain.contracts.AgentRoleSection
import eu.torvian.chatbot.app.domain.contracts.NO_PROJECT_SECTION_TITLE
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rendering tests for the grouped agent-role chip picker shared by both role dialogs.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRoleChipPickerRenderingTest {

    /** Two project sections plus the trailing unassociated group, in display order. */
    private val sections = listOf(
        AgentRoleSection(
            title = "Payments",
            projectId = 10L,
            roles = listOf(role(id = 1L, name = "release", displayName = "Release manager"))
        ),
        AgentRoleSection(
            title = "Research",
            projectId = 20L,
            roles = listOf(role(id = 2L, name = "writer"))
        ),
        AgentRoleSection(
            title = NO_PROJECT_SECTION_TITLE,
            projectId = null,
            roles = listOf(role(id = 3L, name = "triage"))
        )
    )

    /** Every section header and every chip is rendered, and chips carry no project suffix. */
    @Test
    fun `renders headers and project-free chip labels`() = runComposeUiTest {
        setContent {
            AgentRoleChipPicker(sections = sections, selectedRoleIds = emptySet(), onToggleRole = {})
        }

        sections.forEach { section -> onNodeWithText(section.title).assertIsDisplayed() }
        onNodeWithText("release — Release manager").assertIsDisplayed()
        onNodeWithText("writer").assertIsDisplayed()
        onNodeWithText("triage").assertIsDisplayed()
        // The project belongs to the header, so no chip repeats it.
        onNodeWithText("writer — Research").assertDoesNotExist()
    }

    /** Clicking a chip reports exactly that role id to the caller. */
    @Test
    fun `clicking a chip toggles only its own role`() = runComposeUiTest {
        val toggled = mutableListOf<Long>()

        setContent {
            AgentRoleChipPicker(
                sections = sections,
                selectedRoleIds = emptySet(),
                onToggleRole = { roleId -> toggled += roleId }
            )
        }

        onNodeWithText("triage").performClick()

        assertEquals(listOf(3L), toggled)
    }

    /** A selected id renders as selected while its siblings stay unselected. */
    @Test
    fun `selected ids render as selected`() = runComposeUiTest {
        setContent {
            AgentRoleChipPicker(
                sections = sections,
                selectedRoleIds = setOf(2L),
                onToggleRole = {}
            )
        }

        onNodeWithText("writer").assertIsSelected()
        onNodeWithText("triage").assertIsNotSelected()
    }
}

/**
 * @param id Role identifier.
 * @param name Role machine name.
 * @param displayName Optional operator-facing name shown after the separator.
 * @return A role carrying only the fields the picker renders.
 */
private fun role(id: Long, name: String, displayName: String? = null) = AgentRoleDto(
    id = id,
    name = name,
    displayName = displayName,
    modelId = null,
    modelSettingsId = null
)
