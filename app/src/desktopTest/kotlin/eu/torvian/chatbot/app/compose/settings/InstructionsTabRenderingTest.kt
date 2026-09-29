package eu.torvian.chatbot.app.compose.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFilter
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.InstructionLibraryFilter
import eu.torvian.chatbot.app.domain.contracts.InstructionsDialogState
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Rendering tests for the Instructions tab's list page.
 *
 * Covers what the list itself must convey: the usage summary and the "Shared" marker of a row linked
 * by several roles, the generated-message note of a marker row whose text is never stored, the active
 * selection mode in the filter control, and the absence of any reordering control. The detail page's
 * content editor is covered as well, since that is where a row's shared content is changed, and its
 * Delete affordance is covered for both a linked and an unassigned row.
 */
@OptIn(ExperimentalTestApi::class)
class InstructionsTabRenderingTest {

    private fun instruction(
        id: Long,
        name: String,
        linkedRoleIds: Set<Long> = emptySet(),
        type: String = AgentInstructionTypes.CUSTOM,
        message: String = "Be concise"
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        linkedRoleIds = linkedRoleIds
    )

    private fun role(id: Long, name: String, displayName: String? = null, projectId: Long? = null) = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        modelId = 1L,
        modelSettingsId = 2L,
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
     * Renders [InstructionsTab] over the given library and runs [block] against it.
     *
     * @param instructions Rows to render in the list.
     * @param roles The user's roles, labelling the usage summary.
     * @param projects The user's projects, labelling the role options and the project filter.
     * @param filter The role selection shown in the role filter.
     * @param projectFilter The project scope shown in the project filter.
     * @param block Assertions executed against the composed tab.
     */
    private fun setTab(
        instructions: List<AgentInstructionDto>,
        roles: List<AgentRoleDto> = emptyList(),
        projects: List<ProjectDto> = emptyList(),
        filter: InstructionLibraryFilter = InstructionLibraryFilter.All,
        projectFilter: AgentRoleFilter = AgentRoleFilter.AllProjects,
        block: ComposeUiTest.() -> Unit
    ) = runComposeUiTest {
        setContent {
            InstructionsTab(
                state = InstructionsTabState(
                    instructionsUiState = DataState.Success(instructions),
                    selectedInstruction = null,
                    dialogState = InstructionsDialogState.None,
                    filter = filter,
                    projectFilter = projectFilter,
                    roles = roles,
                    rolesById = roles.associateBy { it.id },
                    projects = projects,
                    projectsById = projects.associateBy { it.id }
                ),
                actions = NoOpInstructionsActions,
                onOpenInstructionDetails = {},
                onBackToInstructionList = {}
            )
        }
        block()
    }

    /**
     * Renders [InstructionsTab] on the detail page of [instruction] and runs [block] against it.
     *
     * @param instruction The row whose detail page is rendered.
     * @param roles The user's roles, labelling usage and offering assignment targets.
     * @param projects The user's projects, qualifying the role labels with their project name.
     * @param actions Actions the detail page forwards its interactions to.
     * @param block Assertions executed against the composed tab.
     */
    private fun setDetailTab(
        instruction: AgentInstructionDto,
        roles: List<AgentRoleDto> = emptyList(),
        projects: List<ProjectDto> = emptyList(),
        actions: InstructionsTabActions = NoOpInstructionsActions,
        block: ComposeUiTest.() -> Unit
    ) = runComposeUiTest {
        setContent {
            InstructionsTab(
                state = InstructionsTabState(
                    instructionsUiState = DataState.Success(listOf(instruction)),
                    selectedInstruction = instruction,
                    dialogState = InstructionsDialogState.None,
                    roles = roles,
                    rolesById = roles.associateBy { it.id },
                    projects = projects,
                    projectsById = projects.associateBy { it.id }
                ),
                actions = actions,
                onOpenInstructionDetails = {},
                onBackToInstructionList = {}
            )
        }
        block()
    }

    /**
     * Renders the tab's dialog router over [dialogState] and runs [block] against it.
     *
     * @param dialogState The dialog to render.
     * @param actions Actions the dialog forwards its interactions to.
     * @param block Assertions executed against the composed dialog.
     */
    private fun setDialog(
        dialogState: InstructionsDialogState,
        actions: InstructionsTabActions = NoOpInstructionsActions,
        block: ComposeUiTest.() -> Unit
    ) = runComposeUiTest {
        setContent {
            InstructionsDialogs(dialogState = dialogState, actions = actions)
        }
        block()
    }

    @Test
    fun `a row linked by two roles is marked shared and names its using roles`() {
        val roles = listOf(
            role(1L, "writer", displayName = "Writer", projectId = 1L),
            role(2L, "writer", displayName = "Writer", projectId = 2L)
        )

        setTab(
            instructions = listOf(instruction(5L, "Tone", linkedRoleIds = setOf(1L, 2L))),
            roles = roles,
            projects = listOf(project(1L, "Marketing"), project(2L, "Engineering"))
        ) {
            onNodeWithText("Tone").assertIsDisplayed()
            // The shared marker is what tells the user an edit reaches more than one role.
            onNodeWithText("Shared").assertIsDisplayed()
            // Identically named roles stay distinguishable through their project qualifier.
            onNodeWithText("Used by Writer — Marketing, Writer — Engineering").assertIsDisplayed()
        }
    }

    @Test
    fun `the list names every using role with its project and keeps the unassigned copy`() {
        val roles = listOf(
            role(1L, "writer", displayName = "Writer", projectId = 1L),
            role(3L, "reviewer")
        )

        setTab(
            instructions = listOf(
                instruction(5L, "Tone", linkedRoleIds = setOf(1L, 3L)),
                instruction(6L, "Orphan")
            ),
            roles = roles,
            projects = listOf(project(1L, "Marketing"))
        ) {
            onNodeWithText("Used by Writer — Marketing, reviewer — No project").assertIsDisplayed()
            // A row no role links keeps its unchanged summary.
            onNodeWithText("Unassigned").assertIsDisplayed()
        }
    }

    @Test
    fun `a row no role links is reported as unassigned and carries no shared marker`() {
        setTab(instructions = listOf(instruction(5L, "Tone"))) {
            onNodeWithText("Unassigned").assertIsDisplayed()
            onNodeWithText("Shared").assertDoesNotExist()
        }
    }

    @Test
    fun `a marker row states that its message is generated instead of showing stored text`() {
        // The row stores no message, so the list says where the text comes from.
        setTab(
            instructions = listOf(
                instruction(
                    id = 7L,
                    name = "Available agents",
                    linkedRoleIds = setOf(1L, 2L),
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    message = ""
                )
            ),
            roles = listOf(role(1L, "writer"), role(2L, "coder"))
        ) {
            onNodeWithText("Available agents").assertIsDisplayed()
            onNodeWithText("Message generated per linked role").assertIsDisplayed()
        }
    }

    @Test
    fun `the filter controls show their defaults and the active selection`() {
        setTab(
            instructions = listOf(instruction(5L, "Tone")),
            roles = listOf(role(1L, "writer", displayName = "Writer", projectId = 1L)),
            projects = listOf(project(1L, "Marketing")),
            filter = InstructionLibraryFilter.ByRole(1L)
        ) {
            onNodeWithText("Project").assertIsDisplayed()
            onNodeWithText("All projects").assertIsDisplayed()
            onNodeWithText("Agent role").assertIsDisplayed()
            // The role selection is qualified with its project, so the field is unambiguous.
            onNodeWithText("Writer — Marketing").assertIsDisplayed()
        }
    }

    @Test
    fun `both filters render with their default selection`() {
        setTab(
            instructions = listOf(instruction(5L, "Tone")),
            roles = listOf(role(1L, "writer"))
        ) {
            onNodeWithText("All projects").assertIsDisplayed()
            onNodeWithText("All agent roles").assertIsDisplayed()
        }
    }

    @Test
    fun `inside a project scope the role filter names the role without repeating the project`() {
        setTab(
            instructions = listOf(instruction(5L, "Tone")),
            roles = listOf(role(1L, "writer", displayName = "Writer", projectId = 1L)),
            projects = listOf(project(1L, "Marketing")),
            filter = InstructionLibraryFilter.ByRole(1L),
            projectFilter = AgentRoleFilter.Project(1L)
        ) {
            onNodeWithText("Marketing").assertIsDisplayed()
            onNodeWithText("Writer").assertIsDisplayed()
            // The scope already names the project, so the role entry must not repeat it.
            onNodeWithText("Writer — Marketing").assertDoesNotExist()
        }
    }

    @Test
    fun `the list offers no reordering controls`() {
        // Instruction order belongs to the agent-role editor, so the library has no move actions.
        setTab(
            instructions = listOf(
                instruction(5L, "Tone", linkedRoleIds = setOf(1L)),
                instruction(6L, "Style", linkedRoleIds = setOf(1L))
            ),
            roles = listOf(role(1L, "writer"))
        ) {
            onNodeWithContentDescription("Move up").assertDoesNotExist()
            onNodeWithContentDescription("Move down").assertDoesNotExist()
        }
    }

    @Test
    fun `the detail page qualifies the usage list and the assign picker with the project`() {
        val roles = listOf(
            role(1L, "writer", displayName = "Writer", projectId = 1L),
            role(2L, "coder", displayName = "Coder", projectId = 2L)
        )

        setDetailTab(
            instruction = instruction(5L, "Tone", linkedRoleIds = setOf(1L)),
            roles = roles,
            projects = listOf(project(1L, "Marketing"), project(2L, "Engineering"))
        ) {
            // The usage list entry carries the same qualifier as the summary.
            onNodeWithText("Writer — Marketing").assertIsDisplayed()
            // The picker preselects the first assignable role, rendered with the same format.
            onNodeWithText("Coder — Engineering").assertIsDisplayed()
        }
    }

    @Test
    fun `the detail page disables Delete and explains why while a role uses the row`() {
        setDetailTab(
            instruction = instruction(5L, "Tone", linkedRoleIds = setOf(1L)),
            roles = listOf(role(1L, "writer"))
        ) {
            // The server refuses the delete while the row is linked, so the action is withheld.
            onNodeWithText("Delete").assertIsNotEnabled()
            onNodeWithText(
                "This instruction cannot be deleted while a role uses it. Unassign it from every role " +
                        "above, or delete those roles, first."
            ).assertIsDisplayed()
        }
    }

    @Test
    fun `the detail page enables Delete and carries no blocking note for an unassigned row`() {
        setDetailTab(instruction = instruction(5L, "Tone")) {
            onNodeWithText("Delete").assertIsEnabled()
            onNodeWithText(
                "This instruction cannot be deleted while a role uses it. Unassign it from every role " +
                        "above, or delete those roles, first."
            ).assertDoesNotExist()
        }
    }

    @Test
    fun `the delete confirmation states only the content loss`() {
        setDialog(InstructionsDialogState.DeleteInstruction(instruction(5L, "Tone"))) {
            // Only an unassigned row reaches this dialog, so no unlinking is announced.
            onNodeWithText(
                "Are you sure you want to delete 'Tone'? No agent role uses it, and its content cannot " +
                        "be restored."
            ).assertIsDisplayed()
        }
    }

    @Test
    fun `the detail page offers the content editor for the open row`() {
        val actions = RecordingInstructionsActions()

        setDetailTab(
            instruction = instruction(5L, "Tone", linkedRoleIds = setOf(1L)),
            roles = listOf(role(1L, "writer")),
            actions = actions
        ) {
            onNodeWithText("Edit").performClick()
        }

        assertEquals(5L, actions.editedInstruction?.id)
    }

    @Test
    fun `the edit form shows the stored content and blocks an unchanged save`() {
        setDialog(InstructionsDialogState.EditInstruction(instruction(5L, "Tone"))) {
            onNodeWithText("Tone").assertIsDisplayed()
            onNodeWithText("Be concise").assertIsDisplayed()
            // Nothing was edited yet, so there is nothing to write.
            onNodeWithText("Save").assertIsNotEnabled()
        }
    }

    @Test
    fun `an edited form can be saved`() {
        setDialog(InstructionsDialogState.EditInstruction(instruction(5L, "Tone"), name = "Style")) {
            onNodeWithText("Save").assertIsEnabled()
        }
    }

    @Test
    fun `typing in the form forwards the edited label`() {
        val actions = RecordingInstructionsActions()

        setDialog(InstructionsDialogState.EditInstruction(instruction(5L, "Tone")), actions) {
            onNodeWithText("Tone").performTextReplacement("Style")
        }

        assertEquals("Style", actions.editedName)
    }

    @Test
    fun `typing in the form forwards the edited text`() {
        val actions = RecordingInstructionsActions()

        setDialog(InstructionsDialogState.EditInstruction(instruction(5L, "Tone")), actions) {
            onNodeWithText("Be concise").performTextReplacement("Be brief")
        }

        assertEquals("Be brief", actions.editedMessage)
    }

    @Test
    fun `a blank label is reported and cannot be saved`() {
        setDialog(InstructionsDialogState.EditInstruction(instruction(5L, "Tone"), name = "   ")) {
            onNodeWithText("Name cannot be blank").assertIsDisplayed()
            onNodeWithText("Save").assertIsNotEnabled()
        }
    }

    @Test
    fun `a shared row states that the edit reaches every linked role`() {
        setDialog(
            InstructionsDialogState.EditInstruction(instruction(5L, "Tone", linkedRoleIds = setOf(1L, 2L)))
        ) {
            onNodeWithText(
                "This instruction is used by more than one agent role, so the change applies to all of them."
            ).assertIsDisplayed()
        }
    }

    @Test
    fun `a marker row keeps its message read-only and names where the text comes from`() {
        setDialog(
            InstructionsDialogState.EditInstruction(
                instruction(
                    id = 7L,
                    name = "Available agents",
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    message = ""
                )
            )
        ) {
            onNodeWithText(
                "This instruction stores no text: each linked role resolves its own message."
            ).assertIsDisplayed()
        }
    }
}

/**
 * The tab's actions, all inert: the rendering tests assert the rendered state, never an interaction
 * that would need to reach a ViewModel.
 */
private object NoOpInstructionsActions : InstructionsTabActions {
    override fun onLoadInstructionsAndRoles() = Unit
    override fun onSelectInstruction(instruction: AgentInstructionDto?) = Unit
    override fun onRoleFilterChanged(filter: InstructionLibraryFilter) = Unit
    override fun onProjectFilterChanged(filter: AgentRoleFilter) = Unit
    override fun onStartDeletingInstruction(instruction: AgentInstructionDto) = Unit
    override fun onDeleteInstruction(instructionId: Long) = Unit
    override fun onStartEditingInstruction(instruction: AgentInstructionDto) = Unit
    override fun onEditInstructionNameChanged(name: String) = Unit
    override fun onEditInstructionMessageChanged(message: String) = Unit
    override fun onSaveInstructionEdit() = Unit
    override fun onAssignToRole(roleId: Long) = Unit
    override fun onUnassignFromRole(roleId: Long) = Unit
    override fun onCancelDialog() = Unit
}

/**
 * Actions that record the interaction under test, so a rendering test can assert what the tab
 * requested without reaching a ViewModel.
 */
private class RecordingInstructionsActions : InstructionsTabActions {
    /** The instruction the user asked to edit, or null when no edit was requested. */
    var editedInstruction: AgentInstructionDto? = null

    /** The label the form forwarded, or null when the label was never edited. */
    var editedName: String? = null

    /** The text the form forwarded, or null when the text was never edited. */
    var editedMessage: String? = null

    override fun onLoadInstructionsAndRoles() = Unit
    override fun onSelectInstruction(instruction: AgentInstructionDto?) = Unit
    override fun onRoleFilterChanged(filter: InstructionLibraryFilter) = Unit
    override fun onProjectFilterChanged(filter: AgentRoleFilter) = Unit
    override fun onStartDeletingInstruction(instruction: AgentInstructionDto) = Unit
    override fun onDeleteInstruction(instructionId: Long) = Unit
    override fun onStartEditingInstruction(instruction: AgentInstructionDto) {
        editedInstruction = instruction
    }

    override fun onEditInstructionNameChanged(name: String) {
        editedName = name
    }

    override fun onEditInstructionMessageChanged(message: String) {
        editedMessage = message
    }

    override fun onSaveInstructionEdit() = Unit
    override fun onAssignToRole(roleId: Long) = Unit
    override fun onUnassignFromRole(roleId: Long) = Unit
    override fun onCancelDialog() = Unit
}
