package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.generated.resources.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import org.jetbrains.compose.resources.stringResource

/**
 * Composable for the chat message input area. (PR 21: Implement Input Area UI (E1.S*, E1.S7))
 * Includes:
 * - Message input TextField
 * - Send button
 * - UI for replying to a specific message
 * - Loading indicator on send button (E1.S3)
 * - File reference badges and attach button
 *

 * @param actions Grouped callbacks for input area interactions.
 * @param replyTargetMessage The message being replied to, if any.
 * @param turnExecutionState Lifecycle state used to select the action button.
 * @param modifier Modifier applied to the input container.
 * @param fileReferences List of file references attached to the current message.
 * @param focusRequester Focus requester to programmatically control focus on the text field.
 * @param textFieldState The text field state, shared with parent for cursor persistence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputArea(
    actions: InputAreaActions,
    replyTargetMessage: ChatMessage?,
    turnExecutionState: TurnExecutionState,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    fileReferences: List<FileReference> = emptyList(),
    focusRequester: FocusRequester = remember { FocusRequester() },
    textFieldState: TextFieldState = rememberTextFieldState()
) {
    val isSendButtonEnabled =
        turnExecutionState == TurnExecutionState.IDLE && textFieldState.text.isNotBlank()
    val infiniteTransition = rememberInfiniteTransition(label = "stopping_pulse")
    val pulsingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(modifier = modifier) {
        // Reply Target Banner
        AnimatedVisibility(visible = replyTargetMessage != null) {
            replyTargetMessage?.let {
                ReplyTargetBanner(it, actions.onCancelReply)
            }
        }

        // Input area container with unified styling
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                BasicTextField(
                    state = textFieldState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp) // Ensure a minimum height for the input
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            // Ctrl+Enter to send
                            if (keyEvent.isCtrlPressed && keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                                if (isSendButtonEnabled) {
                                    actions.onSendMessage()
                                    true // Consume the event
                                } else {
                                    false // Do not consume if not enabled
                                }
                            } else {
                                false // Let other key events be handled normally
                            }
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    // Handle regular "Enter" on mobile/IME
                    onKeyboardAction = {
                        if (isSendButtonEnabled) actions.onSendMessage()
                    },
                    lineLimits = if (isExpanded) {
                        TextFieldLineLimits.MultiLine(minHeightInLines = 1)
                    } else {
                        TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 7)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorator = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (textFieldState.undoState.canUndo) // Just a way to check focus/activity
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    else
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (textFieldState.text.isEmpty()) {
                                Text(
                                    "Type a message...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // File reference badges section - part of input field styling
                if (fileReferences.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Attached Files:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        Spacer(Modifier.height(6.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            fileReferences.forEach { ref ->
                                RemovableFileReferenceBadge(
                                    fileReference = ref,
                                    onClick = { actions.onShowFileReferenceDetails(ref) },
                                    onRemove = { actions.onRemoveFileReference(ref) }
                                )
                            }
                        }
                    }
                }

                // Action Row with Expand/Collapse, Attach File, and Send/Stop buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, start = 4.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left side buttons row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Expand/Collapse Toggle Button
                        if (actions.onToggleExpansion != null) {
                            PlainTooltipBox(
                                text = if (isExpanded) "Collapse input area" else "Expand input area"
                            ) {
                                IconButton(
                                    onClick = actions.onToggleExpansion,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .focusProperties { canFocus = false }
                                ) {
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                        contentDescription = if (isExpanded) "Collapse input area" else "Expand input area",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Attach File Button
                        PlainTooltipBox(text = "Attach files") {
                            IconButton(
                                onClick = actions.onAddFileReferences,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachFile,
                                    contentDescription = "Attach files",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Manage Files Button - only show when files are attached
                        if (fileReferences.isNotEmpty()) {
                            PlainTooltipBox(text = "Manage attached files") {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(fileReferences.size.toString())
                                        }
                                    }
                                ) {
                                    IconButton(
                                        onClick = actions.onManageFileReferences,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Manage attached files",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Spacer to push send button to the right
                    Spacer(Modifier.weight(1f))

                    // Render exactly one action for the current turn lifecycle state.
                    when (turnExecutionState) {
                        TurnExecutionState.IDLE -> {
                            PlainTooltipBox(text = stringResource(Res.string.send_message_button_description) + " (Ctrl+Enter)") {
                                FilledIconButton(
                                    onClick = actions.onSendMessage,
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

                        TurnExecutionState.RUNNING -> {
                            PlainTooltipBox(text = "Pause (Finish current step)") {
                                FilledIconButton(
                                    onClick = actions.onPauseSendMessage,
                                    modifier = Modifier.size(48.dp),
                                    enabled = true
                                ) {
                                    Icon(
                                        Icons.Default.Pause,
                                        contentDescription = "Pause (Finish current step)",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }

                        TurnExecutionState.PAUSING -> {
                            PlainTooltipBox(text = "Stop immediately (Force cancel)") {
                                FilledIconButton(
                                    onClick = actions.onCancelSendMessage,
                                    modifier = Modifier.size(48.dp),
                                    enabled = true
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stop immediately (Force cancel)",
                                        modifier = Modifier.graphicsLayer { alpha = pulsingAlpha },
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }

                        TurnExecutionState.STOPPING -> {
                            PlainTooltipBox(text = "Stopping...") {
                                FilledIconButton(
                                    onClick = {},
                                    modifier = Modifier.size(48.dp),
                                    enabled = false
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Stopping...",
                                        modifier = Modifier.graphicsLayer { alpha = pulsingAlpha },
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
