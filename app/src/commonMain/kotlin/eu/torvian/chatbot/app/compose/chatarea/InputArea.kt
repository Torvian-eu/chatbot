package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.generated.resources.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import org.jetbrains.compose.resources.stringResource

/**
 * Composable for the chat message input area. (PR 21: Implement Input Area UI (E1.S*, E1.S7))
 * Includes:
 * - Message input TextField
 * - Send button
 * - UI for replying to a specific message
 * - Loading indicator on send button (E1.S3)
 *
 * @param inputContent The current text content of the input field.
 * @param onUpdateInput Callback for when the input content changes.
 * @param onSendMessage Callback for when the send button is clicked or Enter is pressed.
 * @param onCancelSendMessage Callback for when the user cancels the message sending operation.
 * @param replyTargetMessage The message being replied to, if any.
 * @param onCancelReply Callback to cancel the reply.
 * @param isSendingMessage Indicates if a message is currently being sent.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
fun InputArea(
    inputContent: String,
    onUpdateInput: (String) -> Unit,
    onSendMessage: () -> Unit,
    onCancelSendMessage: () -> Unit,
    replyTargetMessage: ChatMessage?,
    onCancelReply: () -> Unit,
    isSendingMessage: Boolean,
    isExpanded: Boolean = false,
    onToggleExpansion: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isSendButtonEnabled = inputContent.isNotBlank() && !isSendingMessage

    Column(modifier = modifier) {
        // Reply Target Display (E1.S7)
        AnimatedVisibility(
            visible = replyTargetMessage != null,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            replyTargetMessage?.let { message ->
                ReplyTargetBanner(message = message, onCancelReply = onCancelReply)
            }
        }

        // Input area container with unified styling
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                // Main Input Field (E1.S1)
                TextField(
                    value = inputContent,
                    onValueChange = onUpdateInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp), // Ensure a minimum height for the input
                    placeholder = { Text("Type a message...") },
                    singleLine = false, // Allow multiline input
                    maxLines = if (isExpanded) Int.MAX_VALUE else 5, // Remove limit when expanded
                    shape = RoundedCornerShape(20.dp), // Slightly less rounded than container
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (isSendButtonEnabled) {
                                onSendMessage()
                            }
                        }
                    )
                )

                // Action Row with Expand/Collapse and Send/Stop buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Expand/Collapse Toggle Button (left side)
                    if (onToggleExpansion != null) {
                        PlainTooltipBox(
                            text = if (isExpanded) "Collapse input area" else "Expand input area"
                        ) {
                            IconButton(
                                onClick = onToggleExpansion,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                    contentDescription = if (isExpanded) "Collapse input area" else "Expand input area",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.width(40.dp)) // Maintain layout when toggle not available
                    }

                    // Send Button or Stop Button (right side)
                    if (isSendingMessage) { // Stop button to cancel sending (E1.S3)
                        PlainTooltipBox(text = stringResource(Res.string.sending_message_tooltip)) {
                            FilledIconButton(
                                onClick = onCancelSendMessage,
                                modifier = Modifier.size(48.dp),
                                enabled = true
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = stringResource(Res.string.cancel_send_message_button_description),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    } else { // Show Send Button (E1.S2)
                        PlainTooltipBox(text = stringResource(Res.string.send_message_button_description)) {
                            FilledIconButton(
                                onClick = onSendMessage,
                                modifier = Modifier.size(48.dp),
                                enabled = isSendButtonEnabled
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(Res.string.send_message_button_description),
                                    tint = MaterialTheme.colorScheme.onPrimary
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
 * Composable for displaying the banner indicating which message is being replied to. (E1.S7)
 *
 * @param message The message object being replied to.
 * @param onCancelReply Callback to cancel the reply.
 */
@Composable
private fun ReplyTargetBanner(
    message: ChatMessage,
    onCancelReply: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${stringResource(Res.string.replying_to_prefix)} \"${message.content}\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onCancelReply,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(Res.string.cancel_reply_button_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
