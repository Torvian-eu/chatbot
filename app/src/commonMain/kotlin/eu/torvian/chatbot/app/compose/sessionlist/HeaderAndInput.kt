package eu.torvian.chatbot.app.compose.sessionlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox

/**
 * Header section of the session list panel with title and action buttons.
 */
@Composable
fun SessionListHeader(
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
fun NewGroupInputSection(
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
