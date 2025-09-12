package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.common.models.ChatMessage

/**
 * Composable for displaying the content of a message, including editing capabilities.
 *
 * @param message The message data.
 * @param isBeingEdited Whether this message is currently being edited.
 * @param editingContent The current editing content if this message is being edited.
 * @param actions The actions contract, providing the edit message actions.
 * @param contentColor The color to be used for the content, respecting the current theme.
 */
@Composable
fun MessageContent(
    message: ChatMessage,
    isBeingEdited: Boolean,
    editingContent: String?,
    actions: ChatAreaActions,
    contentColor: Color
) {
    if (isBeingEdited) {
        // Message is in editing state
        var localEditingContent by remember(editingContent) {
            mutableStateOf(editingContent ?: message.content)
        }

        // Text field for editing message content
        TextField(
            value = localEditingContent,
            onValueChange = { newValue ->
                localEditingContent = newValue
                actions.onUpdateEditingContent(newValue)
            },
            placeholder = { Text("Edit your message...", color = contentColor.copy(alpha = 0.6f)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )

        // Actions for the edited message (Save, Cancel)
        MessageEditActions(
            onSave = {
                actions.onSaveEditing()
            },
            onCancel = {
                actions.onCancelEditing()
            },
            modifier = Modifier.padding(top = 8.dp) // Padding between text field and actions
        )
    } else {
        // Message is not being edited, just display the text
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor
        )
    }
}

/**
 * Composable for displaying the actions available for an edited message.
 *
 * @param onSave Callback for the save action.
 * @param onCancel Callback for the cancel action.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
private fun MessageEditActions(
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier.Companion
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End, // Align actions to the end
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Save Button
        TextButton(
            onClick = onSave,
            modifier = Modifier.padding(end = 8.dp) // Spacing between buttons
        ) {
            Text("Save", color = MaterialTheme.colorScheme.primary)
        }

        // Cancel Button
        TextButton(
            onClick = onCancel
        ) {
            Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}