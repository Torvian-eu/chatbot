package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFormState
import eu.torvian.chatbot.app.domain.contracts.AgentRoleInstructionDraft
import eu.torvian.chatbot.app.domain.contracts.createEmptyAgentRoleForm
import eu.torvian.chatbot.app.domain.contracts.toDraft
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlin.test.Test

/**
 * Rendering tests for the agent-role form's existing-instruction picker state.
 *
 * The picker offers its rows through a menu popup, which this rendering harness cannot traverse, so
 * these tests cover the control's own state (enabled only while a row may be linked, plus the reason it
 * is disabled) and the selection rules themselves are covered by the unit tests of the selection
 * helper.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRoleInstructionPickerRenderingTest {

    /** The library the picker scans: one reusable `custom` row and one `role` row. */
    private val library = listOf(
        AgentInstructionDto(
            id = 10L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Code style",
            message = "Prefer Kotlin idioms"
        ),
        AgentInstructionDto(
            id = 11L,
            type = AgentInstructionTypes.ROLE,
            name = "Reviewer role",
            message = "You are a reviewer"
        )
    )

    /** The label explaining a disabled picker, shown under the instruction list header. */
    private val emptySelectionNote = "No other library instruction can be linked to this role."

    /**
     * Renders the form dialog over [formState] with [library] and runs [block] against the live draft.
     *
     * @param library The instruction library offered by the picker.
     * @param formState The draft the dialog starts with.
     * @param block Assertions/interactions executed against the dialog and the live draft.
     */
    private fun setFormDialog(
        library: List<AgentInstructionDto>,
        formState: AgentRoleFormState = createEmptyAgentRoleForm(),
        block: ComposeUiTest.(draft: () -> AgentRoleFormState) -> Unit
    ) = runComposeUiTest {
        val state = mutableStateOf(formState)
        setContent {
            AgentRoleFormDialog(
                title = "Add Agent Role",
                formState = state.value,
                models = emptyList(),
                presets = emptyList(),
                settingsById = emptyMap(),
                tools = emptyList(),
                instructions = library,
                workerDisplayNamesById = emptyMap(),
                mcpServerNamesById = emptyMap(),
                roles = emptyList(),
                projects = emptyList(),
                onFormUpdate = { update -> state.value = update(state.value) },
                onSave = { },
                onCancel = { }
            )
        }
        block { state.value }
    }

    @Test
    fun `the picker is enabled while the library still offers a linkable row`() {
        setFormDialog(library = library) { _ ->
            onNodeWithText("Add existing").assertIsEnabled()
            onNodeWithText(emptySelectionNote).assertDoesNotExist()
        }
    }

    @Test
    fun `the picker is disabled while an empty library offers nothing`() {
        setFormDialog(library = emptyList()) { _ ->
            onNodeWithText("Add existing").assertIsNotEnabled()
            // An empty library needs no explanation: there is simply nothing to choose from yet.
            onNodeWithText(emptySelectionNote).assertDoesNotExist()
        }
    }

    @Test
    fun `the picker explains itself when every library row is already accounted for`() {
        // The role links the library's only row, so the disabled control must say why.
        val singleRowLibrary = listOf(library.first())

        setFormDialog(
            library = singleRowLibrary,
            formState = createEmptyAgentRoleForm().copy(instructions = listOf(library.first().toDraft()))
        ) { _ ->
            onNodeWithText("Add existing").assertIsNotEnabled()
            onNodeWithText(emptySelectionNote).assertExists()
        }
    }

    @Test
    fun `a row linked by more than one role is marked as shared before it is edited`() {
        // The marker appears from the reported linking roles, so the user knows an edit here reaches
        // the other role as well.
        setFormDialog(
            library = library,
            formState = createEmptyAgentRoleForm().copy(
                instructions = listOf(
                    AgentRoleInstructionDraft(
                        id = 10L,
                        type = AgentInstructionTypes.CUSTOM,
                        name = "Code style",
                        message = "Prefer Kotlin idioms",
                        linkedRoleIds = setOf(1L, 2L)
                    )
                )
            )
        ) { _ ->
            onNodeWithText("Shared").assertExists()
        }
    }

    @Test
    fun `a row linked by a single role carries no shared marker`() {
        setFormDialog(
            library = library,
            formState = createEmptyAgentRoleForm().copy(
                instructions = listOf(
                    AgentRoleInstructionDraft(
                        id = 10L,
                        type = AgentInstructionTypes.CUSTOM,
                        name = "Code style",
                        message = "Prefer Kotlin idioms",
                        linkedRoleIds = setOf(1L)
                    )
                )
            )
        ) { _ ->
            onNodeWithText("Shared").assertDoesNotExist()
        }
    }
}
