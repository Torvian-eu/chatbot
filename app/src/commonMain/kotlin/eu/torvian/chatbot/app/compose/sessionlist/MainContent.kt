package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatSessionSummary

/**
 * Main content section displaying the list of sessions and groups.
 */
@Composable
fun MainContent(
    groupedSessions: Map<ChatGroup?, List<ChatSessionSummary>>,
    selectedSessionId: Long?,
    editingGroup: ChatGroup?,
    editingGroupNameInput: String,
    onSessionSelected: (Long?) -> Unit,
    groupEditingActions: GroupEditingActions,
    dialogRequestActions: DialogActions
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
