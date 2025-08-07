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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.viewmodel.SessionListActions
import eu.torvian.chatbot.app.viewmodel.SessionListState
import eu.torvian.chatbot.app.viewmodel.UiState
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatSessionSummary

/**
 * Consolidated state for all dialog management in the session list panel.
 */
private sealed class DialogState {
    object None : DialogState()
    data class NewSession(val inputText: String = "") : DialogState()
    data class RenameSession(val session: ChatSessionSummary, val inputText: String) : DialogState()
    data class DeleteSession(val sessionId: Long) : DialogState()
    data class AssignGroup(val sessionId: Long, val groupId: Long?) : DialogState()
    data class DeleteGroup(val groupId: Long) : DialogState()
}

/**
 * Stateless Composable for the session list panel.
 * This component is responsible for:
 * - Displaying the list of chat sessions and groups.
 * - Handling user interactions for selecting, creating, renaming, and deleting sessions/groups.
 * - Managing internal state for dialogs and input fields.
 * - Not managing any ViewModel state directly.
 *
 * @param state The current state contract for the session list panel.
 * @param actions The actions contract for the session list panel.
 */
@Composable
fun SessionListPanel(
    state: SessionListState,
    actions: SessionListActions
) {
    // Consolidated dialog state management
    var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }
    // Collapsible group state
    var expandedGroups by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    fun toggleGroup(groupId: Long) {
        expandedGroups = if (expandedGroups.contains(groupId)) {
            expandedGroups - groupId
        } else {
            expandedGroups + groupId
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SessionListHeader(
            onNewSessionClick = {
                dialogState = DialogState.NewSession()
            },
            onNewGroupClick = actions::onStartCreatingNewGroup
        )

        // --- New Group Input (E6.S3) ---
        NewGroupInputSection(
            isVisible = state.isCreatingNewGroup,
            groupNameInput = state.newGroupNameInput,
            onGroupNameChange = actions::onUpdateNewGroupNameInput,
            onCreateGroup = actions::onCreateNewGroup,
            onCancelCreation = actions::onCancelCreatingNewGroup
        )

        // --- Main Content: Session List (E2.S3, E6.S2) ---
        SessionListContent(
            state = state,
            actions = actions,
            onRenameSession = { session ->
                dialogState = DialogState.RenameSession(
                    session = session,
                    inputText = session.name
                )
            },
            onDeleteSession = { sessionId ->
                dialogState = DialogState.DeleteSession(
                    sessionId = sessionId
                )
            },
            onAssignToGroup = { session ->
                dialogState = DialogState.AssignGroup(
                    sessionId = session.id,
                    groupId = session.groupId
                )
            },
            onDeleteGroup = { group ->
                dialogState = DialogState.DeleteGroup(
                    groupId = group?.id ?: -1L
                )
            },
            expandedGroups = expandedGroups,
            onToggleGroup = ::toggleGroup
        )
    }

    // --- Dialogs ---
    SessionListDialogs(
        dialogState = dialogState,
        state = state,
        actions = actions,
        onDialogStateChange = { dialogState = it }
    )

}

/**
 * Consolidated dialog management for the session list panel.
 */
@Composable
private fun SessionListDialogs(
    dialogState: DialogState,
    state: SessionListState,
    actions: SessionListActions,
    onDialogStateChange: (DialogState) -> Unit
) {
    when (dialogState) {
        is DialogState.NewSession -> {
            NewSessionDialog(
                nameInput = dialogState.inputText,
                onNameInputChange = {
                    onDialogStateChange(dialogState.copy(inputText = it))
                },
                onCreateSession = {
                    actions.onCreateNewSession(dialogState.inputText.ifBlank { null })
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.RenameSession -> {
            dialogState.session.let { session ->
                RenameSessionDialog(
                    nameInput = dialogState.inputText,
                    onNameInputChange = {
                        onDialogStateChange(dialogState.copy(inputText = it))
                    },
                    onRenameSession = {
                        actions.onRenameSession(session, dialogState.inputText)
                        onDialogStateChange(DialogState.None)
                    },
                    onDismiss = { onDialogStateChange(DialogState.None) }
                )
            }
        }

        is DialogState.DeleteSession -> {
            DeleteSessionDialog(
                onDeleteConfirm = {
                    actions.onDeleteSession(dialogState.sessionId)
                    onDialogStateChange(DialogState.None)
                },
                onDismiss = { onDialogStateChange(DialogState.None) }
            )
        }

        is DialogState.AssignGroup -> {
            dialogState.sessionId.let { sessionId ->
                AssignSessionToGroupDialog(
                    session = state.listUiState.dataOrNull?.allSessions?.find { it.id == sessionId },
                    groups = state.listUiState.dataOrNull?.allGroups ?: emptyList(),
                    onAssignToGroup = { sessionId, groupId ->
                        actions.onAssignSessionToGroup(sessionId, groupId)
                        onDialogStateChange(DialogState.None)
                    },
                    onDismiss = { onDialogStateChange(DialogState.None) }
                )
            }
        }

        is DialogState.DeleteGroup -> {
            DeleteGroupDialog(
                onDeleteConfirm = {
                    actions.onDeleteGroup(dialogState.groupId)
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
            .padding(bottom = 8.dp),
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
 * Optimized for performance with stable callbacks and proper keys.
 */
@Composable
private fun SessionListContent(
    state: SessionListState,
    actions: SessionListActions,
    onRenameSession: (ChatSessionSummary) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onAssignToGroup: (ChatSessionSummary) -> Unit,
    onDeleteGroup: (ChatGroup?) -> Unit,
    expandedGroups: Set<Long>,
    onToggleGroup: (Long) -> Unit
) {
    // Create stable callback references to prevent unnecessary recompositions
    val onSessionSelected = remember(actions) { actions::onSessionSelected }
    val onStartRenameGroup = remember(actions) { actions::onStartRenamingGroup }
    val onUpdateEditingGroupNameInput = remember(actions) { actions::onUpdateEditingGroupNameInput }
    val onSaveRenamedGroup = remember(actions) { actions::onSaveRenamedGroup }
    val onCancelRenamingGroup = remember(actions) { actions::onCancelRenamingGroup }
    Box {
        when (val listUiState = state.listUiState) {
            UiState.Loading -> LoadingOverlay(Modifier.fillMaxSize())
            is UiState.Error -> {
                ErrorStateDisplay(
                    error = listUiState.error,
                    onRetry = actions::onRetryLoadingSessions,
                    title = "Failed to load sessions",
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            UiState.Idle -> {
                Text(
                    "No sessions loaded. Click 'New Session' to start.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is UiState.Success -> {
                val sessionListData = listUiState.data

                // Use remember for expensive computation to prevent recalculation on every recomposition
                val groupedEntries = remember(sessionListData) {
                    sessionListData.groupedSessions.entries.toList()
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groupedEntries.forEachIndexed { index, (group, sessions) ->
                        // Add space before group header ONLY if:
                        // 1. It's not the very first group (index > 0)
                        // 2. The *previous* group's list of sessions was not empty
                        if (index > 0) {
                            val previousGroupSessions = groupedEntries[index - 1].value
                            if (previousGroupSessions.isNotEmpty()) {
                                item(key = "spacer_before_${group?.id ?: "ungrouped"}") {
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            }
                        }
                        // Group Header (E6.S4)
                        val groupId = group?.id ?: -1L
                        stickyHeader(key = "header_$groupId") {
                            GroupHeader(
                                group = group,
                                isEditing = group != null && state.editingGroup?.id == group.id,
                                editingName = state.editingGroupNameInput,
                                onEditNameChange = onUpdateEditingGroupNameInput,
                                onSaveRename = onSaveRenamedGroup,
                                onCancelRename = onCancelRenamingGroup,
                                onStartRename = onStartRenameGroup,
                                onDelete = onDeleteGroup,
                                isExpanded = expandedGroups.contains(groupId),
                                onToggleExpand = { onToggleGroup(groupId) },
                                hasItems = sessions.isNotEmpty()
                            )
                        }
                        // Add space after group header if there are sessions in the group
                        val isExpanded = expandedGroups.contains(groupId)
                        if (isExpanded) {
                            if (sessions.isNotEmpty()) {
                                item(key = "spacer_after_$groupId") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                            // Sessions within the group
                            items(
                                items = sessions,
                                key = { "session_${it.id}" }
                            ) { session ->
                                SessionListItem(
                                    session = session,
                                    isSelected = session.id == state.selectedSessionId,
                                    onClick = onSessionSelected,
                                    onRename = onRenameSession,
                                    onDelete = onDeleteSession,
                                    onAssignToGroup = onAssignToGroup
                                )
                            }
                        }
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
 *
 * @param session The session summary to display.
 * @param isSelected Whether this session is currently selected.
 * @param onClick Callback for when the session is clicked.
 * @param onRename Callback for when the rename action is triggered.
 * @param onDelete Callback for when the delete action is triggered.
 * @param onAssignToGroup Callback for when the assign to group action is triggered.
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
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
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
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                onRename(session)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Assign to Group") },
                            onClick = {
                                onAssignToGroup(session)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                onDelete(session.id)
                                showMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Composable for the header of a group in the session list.
 *
 * @param group The group to display. Null for "Ungrouped".
 * @param isEditing Whether the group name is currently being edited.
 * @param editingName The current text in the editing field.
 * @param onEditNameChange Callback for when the editing text changes.
 * @param onSaveRename Callback for when the rename is confirmed.
 * @param onCancelRename Callback for when the rename is cancelled.
 * @param onStartRename Callback for when the rename process starts.
 * @param onDelete Callback for when the group is deleted.
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
    onDelete: (ChatGroup?) -> Unit,
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
            .padding(vertical = 2.dp)
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
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse group" else "Expand group",
                    )
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
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (group != null) {
                                DropdownMenuItem(
                                    text = { Text("Rename Group") },
                                    onClick = {
                                        onStartRename(group)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Delete Group") },
                                    onClick = {
                                        onDelete(group)
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("No actions available") },
                                    onClick = {},
                                    enabled = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
