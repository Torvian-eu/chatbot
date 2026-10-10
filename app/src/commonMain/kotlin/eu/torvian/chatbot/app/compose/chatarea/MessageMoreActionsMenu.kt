package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.common.models.core.ChatMessage

/**
 * Displays the "More" actions menu with additional, less commonly used actions.
 * This menu is designed for extensibility - new actions can be added here without
 * cluttering the main action row. Frequently used actions are rendered directly in
 * [GeneralMessageControls] instead.
 *
 * @param message The message for which actions are displayed.
 * @param messageActions All available actions for the message item.
 * @param expanded Whether the menu is currently expanded.
 * @param onExpandedChange Callback to update the expanded state.
 * @param enabled Whether the menu's actions are clickable. Disabled while an assistant turn is
 * active to prevent destructive edits to a conversation that is still being generated.
 */
@Composable
internal fun MessageMoreActionsMenu(
    message: ChatMessage,
    messageActions: MessageActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Box {
        PlainTooltipBox(text = "More actions") {
            IconButton(
                onClick = { onExpandedChange(true) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            // Insert Message action
            InsertMessageMenuItem(
                message = message,
                onRequestInsertMessage = messageActions.onRequestInsertMessage,
                onDismissMenu = { onExpandedChange(false) },
                enabled = enabled
            )

            // Delete Thread action
            DeleteThreadMenuItem(
                message = message,
                onDeleteThread = messageActions.onDeleteThread,
                onDismissMenu = { onExpandedChange(false) },
                enabled = enabled
            )

            // Future: Add more menu items here as needed
            // e.g., "Pin message", "Bookmark", "Share", etc.
        }
    }
}

/**
 * Menu item for Insert Message action.
 *
 * @param message The message relative to which a new message would be inserted.
 * @param onRequestInsertMessage Callback for the insert action.
 * @param onDismissMenu Callback to close the overflow menu after selection.
 * @param enabled Whether the action is clickable. Disabled while an assistant turn is active.
 */
@Composable
internal fun InsertMessageMenuItem(
    message: ChatMessage,
    onRequestInsertMessage: (ChatMessage) -> Unit,
    onDismissMenu: () -> Unit,
    enabled: Boolean
) {
    DropdownMenuItem(
        text = { Text("Insert Message") },
        onClick = {
            onDismissMenu()
            onRequestInsertMessage(message)
        },
        enabled = enabled,
        leadingIcon = {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
    )
}

/**
 * Menu item for Delete Thread action.
 *
 * @param message The message whose thread would be deleted.
 * @param onDeleteThread Callback for the delete-thread action.
 * @param onDismissMenu Callback to close the overflow menu after selection.
 * @param enabled Whether the action is clickable. Disabled while an assistant turn is active.
 */
@Composable
internal fun DeleteThreadMenuItem(
    message: ChatMessage,
    onDeleteThread: (ChatMessage) -> Unit,
    onDismissMenu: () -> Unit,
    enabled: Boolean
) {
    DropdownMenuItem(
        text = { Text("Delete Thread") },
        onClick = {
            onDismissMenu()
            onDeleteThread(message)
        },
        enabled = enabled,
        leadingIcon = {
            Icon(
                Icons.Default.DeleteSweep,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                }
            )
        }
    )
}
