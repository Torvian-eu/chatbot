package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ConfigDropdown
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.compose.common.StatusBadge
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFilter
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.InstructionLibraryFilter
import eu.torvian.chatbot.app.domain.contracts.InstructionsDialogState
import eu.torvian.chatbot.app.domain.contracts.displayLabel
import eu.torvian.chatbot.app.domain.contracts.instructionFilterOptions
import eu.torvian.chatbot.app.domain.contracts.optionLabel
import eu.torvian.chatbot.app.domain.contracts.roleUsageLabel
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.shared
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * State contract for the Instructions tab.
 *
 * @property instructionsUiState Load state of the instruction library narrowed to [filter] and
 *            [projectFilter].
 * @property selectedInstruction The instruction open in the master-detail view, or null on the list page.
 * @property dialogState The active dialog (currently only the delete confirmation).
 * @property filter The active library selection mode.
 * @property projectFilter The active project scope, narrowing the list and the role filter options.
 * @property roles The user's roles, offered as assignment targets.
 * @property rolesById Role lookup resolving an instruction's linking role ids into labels.
 * @property projects The user's projects, one project-filter option each.
 * @property projectsById Project lookup qualifying every role label with its project name.
 */
data class InstructionsTabState(
    val instructionsUiState: DataState<RepositoryError, List<AgentInstructionDto>>,
    val selectedInstruction: AgentInstructionDto?,
    val dialogState: InstructionsDialogState,
    val filter: InstructionLibraryFilter = InstructionLibraryFilter.All,
    val projectFilter: AgentRoleFilter = AgentRoleFilter.AllProjects,
    val roles: List<AgentRoleDto> = emptyList(),
    val rolesById: Map<Long, AgentRoleDto> = emptyMap(),
    val projects: List<ProjectDto> = emptyList(),
    val projectsById: Map<Long, ProjectDto> = emptyMap()
)

/**
 * Action callbacks for the Instructions tab.
 */
interface InstructionsTabActions {
    /** Reloads the instruction library and the role catalog in parallel. */
    fun onLoadInstructionsAndRoles()

    /** Selects an instruction for the master-detail view, or clears selection when null. */
    fun onSelectInstruction(instruction: AgentInstructionDto?)

    /** Applies a library selection mode to the already loaded rows. */
    fun onRoleFilterChanged(filter: InstructionLibraryFilter)

    /** Applies a project scope to the already loaded rows, narrowing the role filter options. */
    fun onProjectFilterChanged(filter: AgentRoleFilter)

    /** Opens the delete confirmation dialog for [instruction]. */
    fun onStartDeletingInstruction(instruction: AgentInstructionDto)

    /** Deletes an instruction by id; only a row no agent role links can be deleted. */
    fun onDeleteInstruction(instructionId: Long)

    /** Opens the content editor for [instruction], prefilled with its stored content. */
    fun onStartEditingInstruction(instruction: AgentInstructionDto)

    /** Applies an edited label to the open content editor. */
    fun onEditInstructionNameChanged(name: String)

    /** Applies an edited text to the open content editor. */
    fun onEditInstructionMessageChanged(message: String)

    /** Saves the open content editor, which rewrites the shared row. */
    fun onSaveInstructionEdit()

    /** Assigns the selected instruction to [roleId], appending it last in that role's list. */
    fun onAssignToRole(roleId: Long)

    /** Unassigns the selected instruction from [roleId]; the instruction row survives. */
    fun onUnassignFromRole(roleId: Long)

    /** Cancels any dialog. */
    fun onCancelDialog()
}

/**
 * Instructions management tab with separate list and detail pages.
 *
 * The library is shared content: a row can be linked by many roles, so the tab shows which roles use
 * each instruction and lets the user edit the row's content, unlink it from one role or delete the row
 * entirely. Assigning appends the instruction last in the target role's list, and the tab deliberately
 * offers no reordering controls — instruction order belongs to the role editor.
 *
 * The tab stays presentational: it switches between list/detail while the route owns page navigation
 * state and the ViewModel owns the filter, selection and dialogs.
 *
 * @param state Current Instructions tab state from the route.
 * @param actions ViewModel-forwarding actions for the library flows.
 * @param onOpenInstructionDetails Callback invoked when the user opens an instruction's detail page.
 * @param onBackToInstructionList Callback invoked when the user returns to the instruction list.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun InstructionsTab(
    state: InstructionsTabState,
    actions: InstructionsTabActions,
    onOpenInstructionDetails: (AgentInstructionDto) -> Unit,
    onBackToInstructionList: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.instructionsUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading instructions...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load instructions",
                    error = uiState.error,
                    onRetry = { actions.onLoadInstructionsAndRoles() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val selectedInstruction = state.selectedInstruction

                if (selectedInstruction != null) {
                    InstructionDetailPage(
                        instruction = selectedInstruction,
                        roles = state.roles,
                        rolesById = state.rolesById,
                        projectsById = state.projectsById,
                        onBackToList = onBackToInstructionList,
                        onEdit = { actions.onStartEditingInstruction(it) },
                        onDelete = { actions.onStartDeletingInstruction(it) },
                        onAssignToRole = { actions.onAssignToRole(it) },
                        onUnassignFromRole = { actions.onUnassignFromRole(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    InstructionListPage(
                        instructions = uiState.data,
                        filter = state.filter,
                        projectFilter = state.projectFilter,
                        roles = state.roles,
                        rolesById = state.rolesById,
                        projects = state.projects,
                        projectsById = state.projectsById,
                        onInstructionSelected = { instruction -> onOpenInstructionDetails(instruction) },
                        onRoleFilterChanged = { actions.onRoleFilterChanged(it) },
                        onProjectFilterChanged = { actions.onProjectFilterChanged(it) },
                        onReload = { actions.onLoadInstructionsAndRoles() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Instructions will appear here.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadInstructionsAndRoles() }) {
                            Text("Load Instructions")
                        }
                    }
                }
            }
        }
    }

    InstructionsDialogs(
        dialogState = state.dialogState,
        actions = actions
    )
}

/**
 * Full-width page for browsing the user's instruction library.
 *
 * Two filters narrow the list: the project scope and the role selection. Both are applied to the
 * loaded rows by the ViewModel, so the page only renders them and forwards the user's choice; the rows
 * arrive already narrowed. A `spawnable_agents` row is listed like any other instruction, with its
 * generated-message note in place of the text it never stores.
 *
 * @param instructions Instructions to render (already narrowed to [filter] and [projectFilter]).
 * @param filter The active role selection, shown in the role filter.
 * @param projectFilter The active project scope, shown in the project filter.
 * @param roles The user's roles, one role-filter entry per role in scope.
 * @param rolesById Role lookup labelling the role-filter entries.
 * @param projects The user's projects, one project-filter entry each.
 * @param projectsById Project lookup qualifying every role-filter entry with its project name.
 * @param onInstructionSelected Callback invoked when the user opens an instruction's detail page.
 * @param onRoleFilterChanged Callback invoked when the user picks another role selection.
 * @param onProjectFilterChanged Callback invoked when the user picks another project scope.
 * @param onReload Callback invoked when the user reloads an empty listing.
 * @param modifier Modifier applied to the page container.
 */
@Composable
private fun InstructionListPage(
    instructions: List<AgentInstructionDto>,
    filter: InstructionLibraryFilter,
    projectFilter: AgentRoleFilter,
    roles: List<AgentRoleDto>,
    rolesById: Map<Long, AgentRoleDto>,
    projects: List<ProjectDto>,
    projectsById: Map<Long, ProjectDto>,
    onInstructionSelected: (AgentInstructionDto) -> Unit,
    onRoleFilterChanged: (InstructionLibraryFilter) -> Unit,
    onProjectFilterChanged: (AgentRoleFilter) -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Project options mirror the Agent Roles tab: ordered by case-insensitive name, id as tie-break.
    // "No project" is always offered, so the option list stays stable even when no role is unassociated.
    val projectOptions = listOf(AgentRoleFilter.AllProjects) +
            projects.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
                .map { AgentRoleFilter.Project(it.id) } +
            AgentRoleFilter.NoProject

    SettingsListPageTemplate(
        title = "Instructions",
        subtitle = if (instructions.isEmpty()) {
            "No instructions match this filter. Instructions are created from the agent-role editor and can be shared by several roles."
        } else {
            "${instructions.size} instruction(s) • select one to see which roles use it."
        },
        modifier = modifier
    ) {
        // The filters sit on their own full-width row instead of the header, so a long role label does
        // not compete with the title for space.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConfigDropdown(
                selectedItem = projectFilter,
                onItemSelected = onProjectFilterChanged,
                items = projectOptions,
                label = "Project",
                modifier = Modifier.weight(1f),
                itemText = { option ->
                    when (option) {
                        AgentRoleFilter.AllProjects -> option.displayLabel
                        is AgentRoleFilter.Project -> projectsById[option.projectId]?.name
                            ?: option.displayLabel

                        AgentRoleFilter.NoProject -> option.displayLabel
                    }
                }
            )
            ConfigDropdown(
                selectedItem = filter,
                onItemSelected = onRoleFilterChanged,
                items = instructionFilterOptions(roles, projectFilter, projectsById),
                label = "Agent role",
                modifier = Modifier.weight(1f),
                // A concrete scope already names the project for every entry, so the qualifier would be
                // repeated on each option.
                itemText = {
                    it.optionLabel(
                        rolesById = rolesById,
                        projectsById = projectsById,
                        includeProject = projectFilter == AgentRoleFilter.AllProjects
                    )
                }
            )
        }
        if (instructions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "No instructions to show.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Instructions live in your library and can be linked to any of your roles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onReload) {
                    Text("Reload")
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                instructions.forEach { instruction ->
                    InstructionListItem(
                        instruction = instruction,
                        rolesById = rolesById,
                        projectsById = projectsById,
                        onClick = { onInstructionSelected(instruction) }
                    )
                }
            }
        }
    }
}

/**
 * Compact card used for a single instruction row in the library list.
 *
 * The row reports what the user needs to tell shared content from role-specific content: the kind, the
 * stored (or generated) text and the roles that link the row, with a "Shared" badge when more than one
 * role links it.
 *
 * @param instruction Instruction shown in the row.
 * @param rolesById Role lookup resolving the linking role ids into labels.
 * @param projectsById Project lookup qualifying each linking role's label with its project name.
 * @param onClick Callback invoked when the row is activated.
 * @param modifier Modifier applied to the row card.
 */
@Composable
fun InstructionListItem(
    instruction: AgentInstructionDto,
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = instruction.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = instruction.message.ifBlank { generatedMessageNote(instruction) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(text = instruction.type)

                if (instruction.shared) {
                    SharedInstructionBadge()
                }

                Text(
                    text = usedByLabel(instruction, rolesById, projectsById),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One-line summary of the roles that link an instruction.
 *
 * @param instruction The instruction whose usage is summarised.
 * @param rolesById Role lookup resolving the linking role ids into labels.
 * @param projectsById Project lookup qualifying each role's label with its project name.
 * @return "Unassigned" for a row no role links, otherwise the project-qualified role labels.
 */
internal fun usedByLabel(
    instruction: AgentInstructionDto,
    rolesById: Map<Long, AgentRoleDto>,
    projectsById: Map<Long, ProjectDto>
): String {
    val labels = instruction.linkedRoleIds.sorted().map { roleId ->
        roleUsageLabel(roleId, rolesById, projectsById)
    }
    return if (labels.isEmpty()) "Unassigned" else "Used by ${labels.joinToString(", ")}"
}

/**
 * Describes the message of an instruction whose text is generated at read time.
 *
 * The kind stores no message: each linked role resolves its own text, so the row can only say that.
 *
 * @param instruction The instruction whose message is described.
 * @return The note shown in place of the absent text, or an empty string for a kind that stores text.
 */
internal fun generatedMessageNote(instruction: AgentInstructionDto): String =
    if (instruction.type == AgentInstructionTypes.SPAWNABLE_AGENTS) {
        "Message generated per linked role"
    } else {
        ""
    }
