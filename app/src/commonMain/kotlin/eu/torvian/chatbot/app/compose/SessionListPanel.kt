package eu.torvian.chatbot.app.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.viewmodel.SessionListActions
import eu.torvian.chatbot.app.viewmodel.SessionListState
import eu.torvian.chatbot.app.viewmodel.UiState
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatSessionSummary

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
    // Dialog states for sessions (internal UI state, not hoisted)
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var newSessionNameInput by remember { mutableStateOf("") }
    var showRenameSessionDialog by remember { mutableStateOf(false) }
    var renameSessionNameInput by remember { mutableStateOf("") }
    var sessionToRename by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var showDeleteSessionDialog by remember { mutableStateOf(false) }
    var sessionToDeleteId by remember { mutableStateOf<Long?>(null) }
    var showAssignGroupDialog by remember { mutableStateOf(false) }
    var sessionToAssign by remember { mutableStateOf<ChatSessionSummary?>(null) }

    // Dialog states for groups (internal UI state, not hoisted)
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var groupToDeleteId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SessionListHeader(
            onNewSessionClick = { showNewSessionDialog = true },
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
            onSessionSelected = actions::onSessionSelected,
            onRenameSession = { session ->
                sessionToRename = session
                renameSessionNameInput = session.name
                showRenameSessionDialog = true
            },
            onDeleteSession = { sessionId ->
                sessionToDeleteId = sessionId
                showDeleteSessionDialog = true
            },
            onAssignToGroup = { session ->
                sessionToAssign = session
                showAssignGroupDialog = true
            },
            onStartRenameGroup = actions::onStartRenamingGroup,
            onUpdateEditingGroupNameInput = actions::onUpdateEditingGroupNameInput,
            onSaveRenamedGroup = actions::onSaveRenamedGroup,
            onCancelRenamingGroup = actions::onCancelRenamingGroup,
            onDeleteGroup = { group ->
                groupToDeleteId = group?.id
                showDeleteGroupDialog = true
            }
        )
    }

    // --- Dialogs ---
    // New Session Dialog
    NewSessionDialog(
        isVisible = showNewSessionDialog,
        nameInput = newSessionNameInput,
        onNameInputChange = { newSessionNameInput = it },
        onCreateSession = {
            actions.onCreateNewSession(newSessionNameInput.ifBlank { null })
            showNewSessionDialog = false
            newSessionNameInput = ""
        },
        onDismiss = { showNewSessionDialog = false }
    )

    // Rename Session Dialog
    RenameSessionDialog(
        isVisible = showRenameSessionDialog && sessionToRename != null,
        nameInput = renameSessionNameInput,
        onNameInputChange = { renameSessionNameInput = it },
        onRenameSession = {
            sessionToRename?.let { actions.onRenameSession(it, renameSessionNameInput) }
            showRenameSessionDialog = false
            sessionToRename = null
        },
        onDismiss = { 
            showRenameSessionDialog = false
            sessionToRename = null 
        }
    )

    // Delete Session Confirmation Dialog
    DeleteSessionDialog(
        isVisible = showDeleteSessionDialog && sessionToDeleteId != null,
        onDeleteConfirm = {
            sessionToDeleteId?.let { actions.onDeleteSession(it) }
            showDeleteSessionDialog = false
            sessionToDeleteId = null
        },
        onDismiss = { 
            showDeleteSessionDialog = false
            sessionToDeleteId = null 
        }
    )

    // Assign Session to Group Dialog
    AssignSessionToGroupDialog(
        isVisible = showAssignGroupDialog && sessionToAssign != null,
        session = sessionToAssign,
        groups = state.listUiState.dataOrNull?.allGroups ?: emptyList(),
        onAssignToGroup = { sessionId, groupId ->
            actions.onAssignSessionToGroup(sessionId, groupId)
            showAssignGroupDialog = false
            sessionToAssign = null
        },
        onDismiss = { 
            showAssignGroupDialog = false
            sessionToAssign = null 
        }
    )

    // Delete Group Confirmation Dialog
    DeleteGroupDialog(
        isVisible = showDeleteGroupDialog && groupToDeleteId != null,
        onDeleteConfirm = {
            groupToDeleteId?.let { actions.onDeleteGroup(it) }
            showDeleteGroupDialog = false
            groupToDeleteId = null
        },
        onDismiss = { 
            showDeleteGroupDialog = false
            groupToDeleteId = null 
        }
    )
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
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCreateGroup) {
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
    state: SessionListState,
    onSessionSelected: (Long) -> Unit,
    onRenameSession: (ChatSessionSummary) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onAssignToGroup: (ChatSessionSummary) -> Unit,
    onStartRenameGroup: (ChatGroup) -> Unit,
    onUpdateEditingGroupNameInput: (String) -> Unit,
    onSaveRenamedGroup: () -> Unit,
    onCancelRenamingGroup: () -> Unit,
    onDeleteGroup: (ChatGroup?) -> Unit
) {
    Box {
        when (val listUiState = state.listUiState) {
            UiState.Loading -> LoadingOverlay(Modifier.fillMaxSize())
            is UiState.Error -> {
                Text(
                    "Error loading sessions: ${listUiState.error.message}",
                    color = MaterialTheme.colorScheme.error,
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val groupedEntries = sessionListData.groupedSessions.entries.toList()
                    groupedEntries.forEachIndexed { index, (group, sessions) ->
                        // Add space before group header ONLY if:
                        // 1. It's not the very first group (index > 0)
                        // 2. The *previous* group's list of sessions was not empty
                        if (index > 0) {
                            val previousGroupSessions = groupedEntries[index - 1].value
                            if (previousGroupSessions.isNotEmpty()) {
                                item {
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                        // Group Header (E6.S4)
                        stickyHeader {
                            GroupHeader(
                                group = group,
                                isEditing = group != null && state.editingGroup?.id == group.id,
                                editingName = state.editingGroupNameInput,
                                onEditNameChange = onUpdateEditingGroupNameInput,
                                onSaveRename = onSaveRenamedGroup,
                                onCancelRename = onCancelRenamingGroup,
                                onStartRename = onStartRenameGroup,
                                onDelete = onDeleteGroup
                            )
                        }
                        // Add space after group header if there are sessions in the group
                        if (sessions.isNotEmpty()) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                        // Sessions within the group
                        items(sessions, key = { it.id }) { session ->
                            SessionListItem(
                                session = session,
                                isSelected = session.id == state.selectedSessionId,
                                onClick = { onSessionSelected(session.id) },
                                onRename = { onRenameSession(session) },
                                onDelete = { onDeleteSession(session.id) },
                                onAssignToGroup = { onAssignToGroup(session) }
                            )
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
    isVisible: Boolean,
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
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
}

/**
 * Dialog for renaming an existing session.
 */
@Composable
private fun RenameSessionDialog(
    isVisible: Boolean,
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    onRenameSession: () -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = onNameInputChange,
                    label = { Text("New Session Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = onRenameSession,
                    enabled = nameInput.isNotBlank()
                ) { Text("Rename") }
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
 * Dialog for confirming session deletion.
 */
@Composable
private fun DeleteSessionDialog(
    isVisible: Boolean,
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
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
}

/**
 * Dialog for assigning a session to a group.
 */
@Composable
private fun AssignSessionToGroupDialog(
    isVisible: Boolean,
    session: ChatSessionSummary?,
    groups: List<ChatGroup>,
    onAssignToGroup: (Long, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible && session != null) {
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
    isVisible: Boolean,
    onDeleteConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (isVisible) {
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
}

/**
 * Composable for a single session list item.
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
            .background(
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    hovered -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                }
            )
            .combinedClickable(
                onClick = { onClick(session.id) },
                onLongClick = { showMenu = true }
            )
            .hoverable(interactionSource),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 1.dp
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
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            // Show menu icon on hover for more actions
            if (hovered || showMenu) { // Show if hovered or menu is open
                Box { // Wrap in Box for DropdownMenu positioning
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
    onDelete: (ChatGroup?) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .combinedClickable(
                onClick = { /* Not directly clickable, actions are via menu/buttons */ },
                onLongClick = { if (group != null) showMenu = true } // Long click only for actual groups
            ),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isEditing) {
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
                // Group name
                OverflowTooltipText(
                    text = group?.name ?: "Ungrouped",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                // Show menu icon on hover for more actions (only for actual groups)
                if (group != null && (hovered || showMenu)) {
                    Box {
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
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
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
                        }
                    }
                }
            }
        }
    }
}