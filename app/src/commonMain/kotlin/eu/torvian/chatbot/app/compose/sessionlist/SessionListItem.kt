package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.common.models.core.ChatSessionSummary

/**
 * Composable for a single session list item with improved accessibility and visual feedback.
 */
@Composable
fun SessionListItem(
    session: ChatSessionSummary,
    isSelected: Boolean,
    onClick: (Long) -> Unit,
    onRename: (ChatSessionSummary) -> Unit,
    onDelete: (Long) -> Unit,
    onClone: (ChatSessionSummary) -> Unit,
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
                        onClone = onClone,
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
    onClone: (ChatSessionSummary) -> Unit,
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
            text = { Text("Clone") },
            onClick = {
                onClone(session)
                onDismissRequest()
            },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
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
