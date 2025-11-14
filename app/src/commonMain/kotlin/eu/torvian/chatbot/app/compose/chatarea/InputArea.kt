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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox // Import PlainTooltipBox
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.cancel_reply_button_description
import eu.torvian.chatbot.app.generated.resources.replying_to_prefix
import eu.torvian.chatbot.app.generated.resources.send_message_button_description
import eu.torvian.chatbot.common.models.core.ChatMessage
import org.jetbrains.compose.resources.stringResource

/**
 * Composable for the chat message input area. (PR 21: Implement Input Area UI (E1.S*, E1.S7))
 * Includes:
 * - Message input TextField
 * - Send button
 * - UI for replying to a specific message
 * - Loading indicator on send button (E1.S3)
 * - Tool configuration button
 *
 * @param inputContent The current text content of the input field.
 * @param onUpdateInput Callback for when the input content changes.
 * @param onSendMessage Callback for when the send button is clicked or Enter is pressed.
 * @param replyTargetMessage The message being replied to, if any.
 * @param onCancelReply Callback to cancel the reply.
 * @param isSendingMessage Indicates if a message is currently being sent.
 * @param onShowToolConfig Callback to show the tool configuration dialog.
 * @param enabledToolsCount The number of tools currently enabled for the session.
 * @param modifier Modifier to be applied to the component.
 */
@Composable
fun InputArea(
    inputContent: String,
    onUpdateInput: (String) -> Unit,
    onSendMessage: () -> Unit,
    replyTargetMessage: ChatMessage?,
    onCancelReply: () -> Unit,
    isSendingMessage: Boolean,
    onShowToolConfig: () -> Unit,
    enabledToolsCount: Int,
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

        // Main Input Field and Send Button (E1.S1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = inputContent,
                onValueChange = onUpdateInput,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp), // Ensure a minimum height for the input
                placeholder = { Text("Type a message...") },
                singleLine = false, // Allow multiline input
                maxLines = 5, // Limit input area growth
                shape = RoundedCornerShape(24.dp), // Rounded corners for aesthetics
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent
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

            // Tool Configuration Button
            PlainTooltipBox(text = "Configure Tools") {
                BadgedBox(
                    badge = {
                        if (enabledToolsCount > 0) {
                            Badge {
                                Text(enabledToolsCount.toString())
                            }
                        }
                    }
                ) {
                    IconButton(
                        onClick = onShowToolConfig,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Configure Tools"
                        )
                    }
                }
            }

            // Send Button or Loading Indicator
            if (isSendingMessage) { // Loading indicator (E1.S3)
                PlainTooltipBox(text = "Sending message...") {
                    FilledIconButton(
                        onClick = { }, // Can be used as stop action in future PRs
                        modifier = Modifier.size(48.dp),
                        enabled = true
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp
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
