package eu.torvian.chatbot.app.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.*
import eu.torvian.chatbot.app.domain.contracts.SessionListActions
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.domain.contracts.SessionListState
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatSessionSummary

/**
 * Consolidated state for all dialog management in the session list panel.
 */
private sealed class DialogState {
    object None : DialogState()
    data class NewSession(val sessionNameInput: String = "") : DialogState()
    data class RenameSession(val session: ChatSessionSummary, val newSessionNameInput: String) : DialogState()
    data class DeleteSession(val sessionId: Long) : DialogState()
    data class AssignGroup(val sessionId: Long, val groupId: Long?) : DialogState()
    data class DeleteGroup(val groupId: Long) : DialogState()
}

/**
 * Data class to encapsulate group editing related actions.
 */
private data class GroupEditingActions(
    val onUpdateEditingGroupNameInput: (String) -> Unit,
    val onSaveRenamedGroup: () -> Unit,
    val onCancelRenamingGroup: () -> Unit,
    val onStartRenamingGroup: (ChatGroup) -> Unit,
)

/**
 * Data class to encapsulate dialog request actions for sessions and groups.
 */
private data class SessionListDialogRequestActions(
    val onRenameSessionRequested: (ChatSessionSummary) -> Unit,
    val onDeleteSessionRequested: (Long) -> Unit,
    val onAssignToGroupRequested: (ChatSessionSummary) -> Unit,
    val onDeleteGroupRequested: (ChatGroup) -> Unit
)

/**
 * Stateless Composable for the session list panel.
 * This component is responsible for:
 * - Displaying the list of chat sessions and groups based on `UiState`.
 * - Delegating to `SessionListSuccessPanelContent` when data is available.
 * - Displaying loading, error, or idle states.
 *
 * @param state The current state contract for the session list panel.
 * @param actions The actions contract for the session list panel.
 */
@Composable
fun SessionListPanel(
    state: SessionListState,
    actions: SessionListActions
) {
    when (val listUiState = state.listUiState) {
        UiState.Loading -> {
            LoadingOverlay(Modifier.fillMaxSize())
        }

        is UiState.Error -> {
            ErrorStateDisplay(
                error = listUiState.error,
                onRetry = actions::onRetryLoadingSessions,
                title = "Failed to load sessions",
                modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
            )
        }

        UiState.Idle -> { // Should not happen, but just in case
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No sessions loaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        is UiState.Success -> {
            val sessionListData = listUiState.data
            SessionListSuccessPanelContent(
                sessionListData = sessionListData,
                isCreatingNewGroup = state.isCreatingNewGroup,
                newGroupNameInput = state.newGroupNameInput,
                editingGroup = state.editingGroup,
                editingGroupNameInput = state.editingGroupNameInput,
                selectedSessionId = state.selectedSessionId,
                sessionListActions = actions
            )
        }
    }
}

/**
 * Composable that displays the main content of the SessionListPanel
 * when the UI state is `UiState.Success`.
 * This includes the header, new group input, session list, and dialogs.
 *
 * @param sessionListData The successfully loaded session and group data (kept for allSessions/allGroups for dialogs).
 * @param isCreatingNewGroup Whether a new group input field is visible.
 * @param newGroupNameInput The current input for new group name.
 * @param editingGroup The group being edited, if any.
 * @param editingGroupNameInput The current input for editing group name.
 * @param selectedSessionId The ID of the currently selected session.
 * @param sessionListActions The actions contract for the session list panel.
 */
@Composable
private fun SessionListSuccessPanelContent(
    sessionListData: SessionListData,
    isCreatingNewGroup: Boolean,
    newGroupNameInput: String,
    editingGroup: ChatGroup?,
    editingGroupNameInput: String,
    selectedSessionId: Long?,
    sessionListActions: SessionListActions
) {
    // Consolidated dialog state management
    var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }

    // Group editing actions
    val groupEditingActions = remember(sessionListActions) {
        GroupEditingActions(
            onUpdateEditingGroupNameInput = sessionListActions::onUpdateEditingGroupNameInput,
            onSaveRenamedGroup = sessionListActions::onSaveRenamedGroup,
            onCancelRenamingGroup = sessionListActions::onCancelRenamingGroup,
            onStartRenamingGroup = sessionListActions::onStartRenamingGroup
        )
    }

    // Dialog request actions
    val dialogRequestActions = remember(sessionListActions) {
        SessionListDialogRequestActions(
            onRenameSessionRequested = { session ->
                dialogState = DialogState.RenameSession(
                    session = session,
                    newSessionNameInput = session.name
                )
            },
            onDeleteSessionRequested = { sessionId ->
                dialogState = DialogState.DeleteSession(
                    sessionId = sessionId
                )
            },
            onAssignToGroupRequested = { session ->
                dialogState = DialogState.AssignGroup(
                    sessionId = session.id,
                    groupId = session.groupId
                )
            },
            onDeleteGroupRequested = { group ->
                dialogState = DialogState.DeleteGroup(
                    groupId = group.id
                )
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SessionListHeader(
            onNewSessionClick = {
                dialogState = DialogState.NewSession()
            },
            onNewGroupClick = sessionListActions::onStartCreatingNewGroup
        )
        // --- New Group Input (E6.S3) ---
        NewGroupInputSection(
            isVisible = isCreatingNewGroup,
            groupNameInput = newGroupNameInput,
            onGroupNameChange = sessionListActions::onUpdateNewGroupNameInput,
            onCreateGroup = sessionListActions::onCreateNewGroup,
            onCancelCreation = sessionListActions::onCancelCreatingNewGroup
        )
        // --- Main Content: Session List (E2.S3, E6.S2) ---
        SessionListContent(
            groupedSessions = sessionListData.groupedSessions,
            selectedSessionId = selectedSessionId,
            editingGroup = editingGroup,
            editingGroupNameInput = editingGroupNameInput,
            onSessionSelected = sessionListActions::onSessionSelected,
            groupEditingActions = groupEditingActions,
            dialogRequestActions = dialogRequestActions
        )
    }
    // --- Dialogs ---
    SessionListDialogs(
        dialogState = dialogState,
        allSessions = sessionListData.allSessions,
        allGroups = sessionListData.allGroups,
        sessionListActions = sessionListActions,
        onDialogStateChange = { dialogState = it }
    )
}

/**
 * Consolidated dialog management for the session list panel.
 */
@Composable
private fun SessionListDialogs(
    dialogState: DialogState,
    allSessions: List<ChatSessionSummary>,
    allGroups: List<ChatGroup>,
    sessionListActions: SessionListActions,
    onDialogStateChange: (DialogState) -> Unit
) {
    when (dialogState) {
        is DialogState.NewSession -> {
            NewSessionDialog(
                nameInput = dialogState.sessionNameInput,
                onNameInputChange = {
                    onDialogStateChange(dialogState.copy(sessionNameInput = it))
                },
                onCreateSession = {
                    sessionListActions.onCreateNewSession(dialogState.sessionNameInput.ifBlank { null })
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.RenameSession -> {
            RenameSessionDialog(
                nameInput = dialogState.newSessionNameInput,
                onNameInputChange = {
                    onDialogStateChange(dialogState.copy(newSessionNameInput = it))
                },
                onRenameSession = {
                    sessionListActions.onRenameSession(dialogState.session, dialogState.newSessionNameInput)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.DeleteSession -> {
            DeleteSessionDialog(
                onDeleteConfirm = {
                    sessionListActions.onDeleteSession(dialogState.sessionId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.AssignGroup -> {
            AssignSessionToGroupDialog(
                session = allSessions.find { it.id == dialogState.sessionId },
                groups = allGroups,
                onAssignToGroup = { sessionId, groupId ->
                    sessionListActions.onAssignSessionToGroup(sessionId, groupId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.DeleteGroup -> {
            DeleteGroupDialog(
                onDeleteConfirm = {
                    sessionListActions.onDeleteGroup(dialogState.groupId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        DialogState.None -> { /* No dialog to show */
        }
    }
}

/**
 * Header section of the session list panel with title and action buttons.
 */
@Composable
private fun SessionListHeader(
    onNewSessionClick: () -> Unit,
    onNewGroupClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OverflowTooltipText(
            text = "Chat Sessions",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
        Row {
            // New Session Button
            PlainTooltipBox(text = "Create new session") {
                FilledIconButton(
                    onClick = onNewSessionClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New session")
                }
            }
            Spacer(Modifier.width(8.dp))
            // New Group Button
            PlainTooltipBox(text = "Create new group") {
                FilledIconButton(
                    onClick = onNewGroupClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New group")
                }
            }
        }
    }
}

/**
 * Section for creating a new group with input field and action buttons.
 * Includes validation and improved UX.
 */
@Composable
private fun NewGroupInputSection(
    isVisible: Boolean,
    groupNameInput: String,
    onGroupNameChange: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onCancelCreation: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val isValid = groupNameInput.trim().isNotBlank()
        val hasInput = groupNameInput.isNotBlank()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = groupNameInput,
                onValueChange = onGroupNameChange,
                label = { Text(text = "New Group Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                isError = hasInput && !isValid,
                supportingText = if (hasInput && !isValid) {
                    { Text("Group name cannot be empty") }
                } else null,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onCreateGroup,
                enabled = isValid
            ) {
                Icon(Icons.Default.Check, contentDescription = "Create Group")
            }
            IconButton(onClick = onCancelCreation) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
    }
}

/**
 * Main content section displaying the list of sessions and groups.
 */
@Composable
private fun SessionListContent(
    groupedSessions: Map<ChatGroup?, List<ChatSessionSummary>>,
    selectedSessionId: Long?,
    editingGroup: ChatGroup?,
    editingGroupNameInput: String,
    onSessionSelected: (Long?) -> Unit,
    groupEditingActions: GroupEditingActions,
    dialogRequestActions: SessionListDialogRequestActions
) {
    // Collapsible group state
    var collapsedGroups by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }

    // Function to toggle group expansion/collapse
    fun onToggleGroup(groupId: Long) {
        collapsedGroups = if (collapsedGroups.contains(groupId)) {
            collapsedGroups - groupId
        } else {
            collapsedGroups + groupId
        }
    }

    // Remember grouped entries to avoid recomposition
    val groupedEntries = remember(groupedSessions) {
        groupedSessions.entries.toList()
    }

    // Lazy list state for scrollbars
    val lazyListState = rememberLazyListState()
    ScrollbarWrapper(
        listState = lazyListState,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            groupedEntries.forEachIndexed { index, (group, sessions) ->
                // Group Header (E6.S4)
                val groupId = group?.id ?: -1L
                item(key = "header_$groupId") {
                    Column {
                        // Add space before group header ONLY if:
                        // 1. It's not the very first group (index > 0)
                        // 2. The *previous* group is expanded
                        // 3. The *previous* group's list of sessions was not empty
                        if (index > 0) {
                            val previousGroupEntry = groupedEntries[index - 1]
                            val previousGroupId = previousGroupEntry.key?.id ?: -1L
                            val previousGroupSessions = previousGroupEntry.value
                            if (!collapsedGroups.contains(previousGroupId) && previousGroupSessions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                        GroupHeader(
                            group = group,
                            isEditing = group != null && editingGroup?.id == group.id,
                            editingName = editingGroupNameInput,
                            onEditNameChange = groupEditingActions.onUpdateEditingGroupNameInput,
                            onSaveRename = groupEditingActions.onSaveRenamedGroup,
                            onCancelRename = groupEditingActions.onCancelRenamingGroup,
                            onStartRename = groupEditingActions.onStartRenamingGroup,
                            onDeleteRequested = dialogRequestActions.onDeleteGroupRequested,
                            isExpanded = !collapsedGroups.contains(groupId),
                            onToggleExpand = { onToggleGroup(groupId) },
                            hasItems = sessions.isNotEmpty()
                        )
                    }
                }
                // Show sessions only if the group is expanded
                val isExpanded = !collapsedGroups.contains(groupId)
                if (isExpanded) {
                    items(
                        items = sessions,
                        key = { "session_${it.id}" }
                    ) { session ->
                        SessionListItem(
                            session = session,
                            isSelected = session.id == selectedSessionId,
                            onClick = onSessionSelected,
                            onRename = dialogRequestActions.onRenameSessionRequested,
                            onDelete = dialogRequestActions.onDeleteSessionRequested,
                            onAssignToGroup = dialogRequestActions.onAssignToGroupRequested
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for creating a new session.
 */
@Composable
private fun NewSessionDialog(
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Chat Session") },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameInputChange,
                label = { Text("Session Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onCreateSession) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for renaming an existing session with validation.
 */
@Composable
private fun RenameSessionDialog(
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    onRenameSession: () -> Unit,
    onDismiss: () -> Unit
) {
    val isValid = nameInput.trim().isNotBlank()
    val hasInput = nameInput.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameInputChange,
                label = { Text("New Session Name") },
                singleLine = true,
                isError = hasInput && !isValid,
                supportingText = if (hasInput && !isValid) {
                    { Text("Session name cannot be empty") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = onRenameSession,
                enabled = isValid
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for confirming session deletion.
 */
@Composable
private fun DeleteSessionDialog(
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Session?") },
        text = { Text("Are you sure you want to delete this session and all its messages? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onDeleteConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for assigning a session to a group.
 */
@Composable
private fun AssignSessionToGroupDialog(
    session: ChatSessionSummary?,
    groups: List<ChatGroup>,
    onAssignToGroup: (Long, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    if (session != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Assign Session to Group") },
            text = {
                Column {
                    Text("Select a group for '${session.name}':")
                    Spacer(Modifier.height(8.dp))
                    // Option for "Ungrouped"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAssignToGroup(session.id, null)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = session.groupId == null,
                            onClick = null // Handled by row click
                        )
                        Text("Ungrouped")
                    }
                    // Options for existing groups
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAssignToGroup(session.id, group.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = session.groupId == group.id,
                                onClick = null // Handled by row click
                            )
                            Text(group.name)
                        }
                    }
                }
            },
            confirmButton = {
                // No explicit confirm button needed, selection triggers action
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Dialog for confirming group deletion.
 */
@Composable
private fun DeleteGroupDialog(
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Group?") },
        text = { Text("Are you sure you want to delete this group? Any sessions assigned to it will become 'Ungrouped'. This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onDeleteConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Composable for a single session list item with improved accessibility and visual feedback.
 */
@Composable
private fun SessionListItem(
    session: ChatSessionSummary,
    isSelected: Boolean,
    onClick: (Long) -> Unit,
    onRename: (ChatSessionSummary) -> Unit,
    onDelete: (Long) -> Unit,
    onAssignToGroup: (ChatSessionSummary) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(session.id) },
                onLongClick = { showMenu = true },
                role = Role.Button
            )
            .hoverable(interactionSource)
            .semantics {
                selected = isSelected
                contentDescription = "Chat session: ${session.name}"
            },
        color = when {
            isSelected -> MaterialTheme.colorScheme.surfaceContainerHighest
            hovered -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Session Name
            OverflowTooltipText(
                text = session.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Show menu icon on hover for more actions
            if (hovered || showMenu) { // Show if hovered or menu is open
                Box { // Wrap in Box for DropdownMenu positioning
                    PlainTooltipBox(text = "More actions for session '${session.name}'", showDelay = 1000L) {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More actions for session ${session.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SessionItemActionsDropdown(
                        session = session,
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        onRename = onRename,
                        onAssignToGroup = onAssignToGroup,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}

/**
 * Composable for the dropdown menu within a SessionListItem.
 */
@Composable
private fun SessionItemActionsDropdown(
    session: ChatSessionSummary,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onRename: (ChatSessionSummary) -> Unit,
    onAssignToGroup: (ChatSessionSummary) -> Unit,
    onDelete: (Long) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = {
                onRename(session)
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
        )
        DropdownMenuItem(
            text = { Text("Assign to Group") },
            onClick = {
                onAssignToGroup(session)
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = {
                onDelete(session.id)
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            colors = MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error
            )
        )
    }
}


/**
 * Composable for the header of a group in the session list.
 */
@Composable
private fun GroupHeader(
    group: ChatGroup?,
    isEditing: Boolean,
    editingName: String,
    onEditNameChange: (String) -> Unit,
    onSaveRename: () -> Unit,
    onCancelRename: () -> Unit,
    onStartRename: (ChatGroup) -> Unit,
    onDeleteRequested: (ChatGroup) -> Unit,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    hasItems: Boolean
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {}, // No-op for click on free area
                onLongClick = { showMenu = true },
                role = Role.Button
            )
            .hoverable(interactionSource),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expand/Collapse Button - only show if the group has items
            if (hasItems) {
                PlainTooltipBox(text = if (isExpanded) "Collapse group" else "Expand group", showDelay = 1000L) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse group" else "Expand group",
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            } else {
                // Add a spacer with the same width to maintain alignment
                Spacer(Modifier.width(32.dp))
            }
            if (isEditing && group != null) {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = onEditNameChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onSaveRename, enabled = editingName.isNotBlank()) {
                    Icon(Icons.Default.Check, contentDescription = "Save Group Name")
                }
                IconButton(onClick = onCancelRename) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel Rename")
                }
            } else {
                OverflowTooltipText(
                    text = group?.name ?: "Ungrouped",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (hovered || showMenu) {
                    Box {
                        // Only show group-specific actions for actual groups
                        if (group != null) {
                            PlainTooltipBox(text = "More actions for group '${group.name}'", showDelay = 1000L) {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "More actions for group ${group.name}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        GroupHeaderActionsDropdown(
                            group = group,
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            onStartRename = onStartRename,
                            onDeleteRequested = onDeleteRequested
                        )
                    }
                }
            }
        }
    }
}

/**
 * Composable for the dropdown menu within a GroupHeader.
 */
@Composable
private fun GroupHeaderActionsDropdown(
    group: ChatGroup?,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onStartRename: (ChatGroup) -> Unit,
    onDeleteRequested: (ChatGroup) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        if (group != null) { // Actions for actual groups
            DropdownMenuItem(
                text = { Text("Rename Group") },
                onClick = {
                    onStartRename(group)
                    onDismissRequest()
                },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Delete Group") },
                onClick = {
                    onDeleteRequested(group)
                    onDismissRequest()
                },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error
                )
            )
        } else { // For "Ungrouped" section, no actions are available
            DropdownMenuItem(
                text = { Text("No actions available") },
                onClick = {},
                enabled = false
            )
        }
    }
}