package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.common.models.core.ChatGroup

/**
 * Composable for the header of a group in the session list.
 */
@Composable
fun GroupHeader(
    group: ChatGroup?,
    isEditing: Boolean,
    editingName: String,
    onEditNameChange: (String) -> Unit,
    onSaveRename: () -> Unit,
    onCancelRename: () -> Unit,
    onStartRename: (ChatGroup) -> Unit,
    onDeleteRequested: (Long) -> Unit,
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
    onDeleteRequested: (Long) -> Unit
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
                    onDeleteRequested(group.id)
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

