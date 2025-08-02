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
        // --- Header and New Session/Group Buttons ---
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
                        onClick = { showNewSessionDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                }
                Spacer(Modifier.width(8.dp))
                // New Group Button
                PlainTooltipBox(text = "Create new group") {
                    FilledIconButton(
                        onClick = actions::onStartCreatingNewGroup,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New group")
                    }
                }
            }
        }

        // --- New Group Input (E6.S3) ---
        AnimatedVisibility(
            visible = state.isCreatingNewGroup,
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
                    value = state.newGroupNameInput,
                    onValueChange = actions::onUpdateNewGroupNameInput,
                    label = { Text(text = "New Group Name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = actions::onCreateNewGroup) {
                    Icon(Icons.Default.Check, contentDescription = "Create Group")
                }
                IconButton(onClick = actions::onCancelCreatingNewGroup) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel")
                }
            }
        }

        // --- Main Content: Session List (E2.S3, E6.S2) ---
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
                                    onEditNameChange = actions::onUpdateEditingGroupNameInput,
                                    onSaveRename = actions::onSaveRenamedGroup,
                                    onCancelRename = actions::onCancelRenamingGroup,
                                    onStartRename = actions::onStartRenamingGroup,
                                    onDelete = { g ->
                                        groupToDeleteId = g?.id
                                        showDeleteGroupDialog = true
                                    }
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
                                    onClick = { actions.onSessionSelected(session.id) },
                                    onRename = { s ->
                                        sessionToRename = s
                                        renameSessionNameInput = s.name
                                        showRenameSessionDialog = true
                                    },
                                    onDelete = { sId ->
                                        sessionToDeleteId = sId
                                        showDeleteSessionDialog = true
                                    },
                                    onAssignToGroup = { s ->
                                        sessionToAssign = s
                                        showAssignGroupDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Dialogs ---
    // New Session Dialog (E2.S1)
    if (showNewSessionDialog) {
        AlertDialog(
            onDismissRequest = { showNewSessionDialog = false },
            title = { Text("Create New Chat Session") },
            text = {
                OutlinedTextField(
                    value = newSessionNameInput,
                    onValueChange = { newSessionNameInput = it },
                    label = { Text("Session Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        actions.onCreateNewSession(newSessionNameInput.ifBlank { null })
                        showNewSessionDialog = false
                        newSessionNameInput = ""
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewSessionDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename Session Dialog (E2.S5)
    if (showRenameSessionDialog && sessionToRename != null) {
        AlertDialog(
            onDismissRequest = { showRenameSessionDialog = false; sessionToRename = null },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = renameSessionNameInput,
                    onValueChange = { renameSessionNameInput = it },
                    label = { Text("New Session Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        sessionToRename?.let { actions.onRenameSession(it, renameSessionNameInput) }
                        showRenameSessionDialog = false
                        sessionToRename = null
                    },
                    enabled = renameSessionNameInput.isNotBlank()
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameSessionDialog = false; sessionToRename = null }) { Text("Cancel") }
            }
        )
    }

    // Delete Session Confirmation Dialog (E2.S6)
    if (showDeleteSessionDialog && sessionToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteSessionDialog = false; sessionToDeleteId = null },
            title = { Text("Delete Session?") },
            text = { Text("Are you sure you want to delete this session and all its messages? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        sessionToDeleteId?.let { actions.onDeleteSession(it) }
                        showDeleteSessionDialog = false
                        sessionToDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSessionDialog = false; sessionToDeleteId = null }) { Text("Cancel") }
            }
        )
    }

    // Assign Session to Group Dialog (E6.S1)
    if (showAssignGroupDialog && sessionToAssign != null) {
        val currentGroups = state.listUiState.dataOrNull?.allGroups ?: emptyList() // Data from uiState.uiState.data

        AlertDialog(
            onDismissRequest = { showAssignGroupDialog = false; sessionToAssign = null },
            title = { Text("Assign Session to Group") },
            text = {
                Column {
                    Text("Select a group for '${sessionToAssign?.name}':")
                    Spacer(Modifier.height(8.dp))
                    // Option for "Ungrouped"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sessionToAssign?.let { actions.onAssignSessionToGroup(it.id, null) }
                                showAssignGroupDialog = false
                                sessionToAssign = null
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sessionToAssign?.groupId == null,
                            onClick = null // Handled by row click
                        )
                        Text("Ungrouped")
                    }
                    // Options for existing groups
                    currentGroups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sessionToAssign?.let { actions.onAssignSessionToGroup(it.id, group.id) }
                                    showAssignGroupDialog = false
                                    sessionToAssign = null
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sessionToAssign?.groupId == group.id,
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
                TextButton(onClick = { showAssignGroupDialog = false; sessionToAssign = null }) { Text("Cancel") }
            }
        )
    }

    // Delete Group Confirmation Dialog (E6.S6)
    if (showDeleteGroupDialog && groupToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false; groupToDeleteId = null },
            title = { Text("Delete Group?") },
            text = { Text("Are you sure you want to delete this group? Any sessions assigned to it will become 'Ungrouped'. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        groupToDeleteId?.let { actions.onDeleteGroup(it) }
                        showDeleteGroupDialog = false
                        groupToDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false; groupToDeleteId = null }) { Text("Cancel") }
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
    onClick: (ChatSessionSummary) -> Unit,
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
                onClick = { onClick(session) },
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
//            .padding(top = 10.dp, bottom = 4.dp)
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

