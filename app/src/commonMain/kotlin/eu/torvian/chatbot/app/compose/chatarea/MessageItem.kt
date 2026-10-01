package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.chat.reasoning.deriveReasoningDisplay
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.usage_dialog_header_description
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall
import org.jetbrains.compose.resources.stringResource

/**
 * Displays one message item and adapts search occurrence geometry from content coordinates to item coordinates.
 *
 * @param message The message data.
 * @param allMessagesMap A map of all messages in the session for efficient lookup.
 * @param allRootMessageIds A sorted list of all root message IDs in the session.
 * @param actions Grouped callbacks for message item interactions.
 * @param editingMessage The message currently being edited.
 * @param editingContent The content of the message currently being edited.
 * @param editingFileReferences The file references of the message currently being edited.
 * @param editingBasePathOverride The base path override for editing file references.
 * @param modelsById Map of model IDs to LLMModel objects for displaying model names with graceful degradation.
 * @param toolCallsForMessage List of tool calls associated with this message.
 * @param isCollapsed Whether this message is currently collapsed.
 * @param isCollapsible Whether this message can be collapsed (content length > threshold).
 * @param isReasoningExpanded Whether the reasoning section of this message is currently expanded. Collapsed by
 *        default, including while the message is still streaming.
 * @param onToggleReasoningSection Invoked when the user toggles the reasoning section of a message.
 * @param turnExecutionState Lifecycle state of the active assistant turn; disables actions that
 * start a new LLM turn (Regenerate, Branch & Continue) while a turn is active.
 * @param searchContext optional in-session search context for highlights and selected-result
 * geometry reporting. When `null`, the message renders without search-specific measurements.
 * @param modifier Modifier applied to the outer message container.
 */
@Composable
fun MessageItem(
    message: ChatMessage,
    allMessagesMap: Map<Long, ChatMessage>,
    allRootMessageIds: List<Long>,
    actions: MessageActions,
    editingMessage: ChatMessage?,
    editingContent: String?,
    editingFileReferences: List<FileReference>,
    editingBasePathOverride: String?,
    modifier: Modifier = Modifier,
    modelsById: Map<Long, LLMModel> = emptyMap(),
    toolCallsForMessage: List<ToolCall> = emptyList(),
    isCollapsed: Boolean = false,
    isCollapsible: Boolean = false,
    isReasoningExpanded: Boolean = false,
    onToggleReasoningSection: (Long) -> Unit = {},
    turnExecutionState: TurnExecutionState = TurnExecutionState.IDLE,
    searchContext: MessageSearchContext? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val containerColor = when (message.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.surfaceContainerLow
        ChatMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (message.role) {
        ChatMessage.Role.USER -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatMessage.Role.ASSISTANT -> MaterialTheme.colorScheme.onSurface
    }
    val isSearchResult = searchContext?.matches?.isNotEmpty() == true
    val isCurrentSearchResult = searchContext?.selectedMatch != null
    val searchBorderColor = when {
        isCurrentSearchResult -> MaterialTheme.colorScheme.primary
        isSearchResult -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
        else -> null
    }
    var contentTopInItem by remember(message.id) { mutableStateOf(0f) }
    var selectedOccurrenceCenterInContent by remember(message.id) { mutableStateOf<Float?>(null) }

    LaunchedEffect(contentTopInItem, selectedOccurrenceCenterInContent, searchContext) {
        val onSelectedOccurrenceCenterInItemChanged = searchContext?.onSelectedOccurrenceCenterInContentChanged
            ?: return@LaunchedEffect
        onSelectedOccurrenceCenterInItemChanged(
            selectedOccurrenceCenterInContent?.let { contentTopInItem + it }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (searchBorderColor != null) {
                    Modifier.border(width = if (isCurrentSearchResult) 2.dp else 1.dp, color = searchBorderColor, shape = RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .hoverable(interactionSource)
            .padding(12.dp)
    ) {
        // Role and Name (e.g., "You:" or "AI:" or model name)
        val displayName = when (message.role) {
            ChatMessage.Role.USER -> "You"
            ChatMessage.Role.ASSISTANT -> {
                // Try to get model name from modelsById, fallback to "AI" or model ID
                (message as? ChatMessage.AssistantMessage)?.modelId?.let { modelId ->
                    modelsById[modelId]?.let { model ->
                        model.displayName ?: model.name
                    } ?: "Model ID: $modelId" // Graceful degradation
                } ?: "AI" // No model ID available
            }
        }
        // Tooltip and click target appear together for the header that offers the details dialog.
        val usageDetailsMessage = (message as? ChatMessage.AssistantMessage)
            ?.takeIf { it.offersUsageDetails() }
        val usageHeaderDescription = stringResource(Res.string.usage_dialog_header_description)
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (usageDetailsMessage != null) {
                PlainTooltipBox(
                    text = usageHeaderDescription,
                    showDelay = 500L
                ) {
                    MessageHeaderLabel(
                        displayName = displayName,
                        contentColor = contentColor,
                        modifier = Modifier.clickable(onClickLabel = usageHeaderDescription) {
                            actions.onShowMessageUsageDetails(usageDetailsMessage)
                        }
                    )
                }
            } else {
                MessageHeaderLabel(displayName = displayName, contentColor = contentColor)
            }
            // Collapse/Expand button - only show for collapsible messages
            if (isCollapsible) {
                PlainTooltipBox(
                    text = if (isCollapsed) "Expand message" else "Collapse message",
                    showDelay = 500L
                ) {
                    IconButton(
                        onClick = { actions.onToggleMessageCollapsed(message.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (isCollapsed) "Expand message" else "Collapse message",
                            tint = contentColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // Reasoning section: the message's own items decide whether there is one at all, so a generation that
        // produced no reasoning renders unchanged, in-flight or completed. Keyed on the message so the item walk and
        // the joined plaintext run once per snapshot: every streamed delta recomposes every visible message, and an
        // unchanged one must not re-derive its reasoning for that.
        val reasoningDisplay = remember(message) {
            (message as? ChatMessage.AssistantMessage)?.deriveReasoningDisplay()
        }
        if (reasoningDisplay != null) {
            AssistantMessageReasoningSection(
                display = reasoningDisplay,
                isExpanded = isReasoningExpanded,
                contentColor = contentColor,
                onToggle = { onToggleReasoningSection(message.id) }
            )
            // Separates the reasoning from the answer it belongs to, which would otherwise read as one block.
            Spacer(Modifier.height(8.dp))
        }

        // Message Content - conditionally show editing UI or display content
        Box(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                contentTopInItem = coordinates.positionInParent().y
            }
        ) {
            MessageContent(
                message = message,
                isBeingEdited = editingMessage?.id == message.id,
                editingContent = if (editingMessage?.id == message.id) editingContent else null,
                editingFileReferences = if (editingMessage?.id == message.id) editingFileReferences else emptyList(),
                editingBasePathOverride = if (editingMessage?.id == message.id) editingBasePathOverride else null,
                messageActions = actions,
                contentColor = contentColor,
                isCollapsed = isCollapsed,
                searchContext = searchContext?.copy(
                    onSelectedOccurrenceCenterInContentChanged = { selectedOccurrenceCenterInContent = it }
                ),
            )
        }

        // Incompletion notice: shows the reason the message stopped early, right next to whatever partial
        // content was received. Suppressed while the message is being edited, because saving the edit
        // clears the state server-side; the condition rules out completed messages and the placeholder of
        // the turn that is still streaming.
        if (message is ChatMessage.AssistantMessage &&
            message.showsIncompleteNotice &&
            editingMessage?.id != message.id
        ) {
            AssistantMessageCompletionNotice(message = message, contentColor = contentColor)
        }

        // Tool Call Badges (for assistant messages)
        if (message.role == ChatMessage.Role.ASSISTANT && toolCallsForMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ToolCallBadges(
                toolCalls = toolCallsForMessage,
                onToolCallClick = actions.onShowToolCallDetails
            )
        }

        // File Reference Badges (for messages with file references)
        if (message.fileReferences.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Files",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            FileReferenceBadgeRow(
                fileReferences = message.fileReferences,
                onFileReferenceClick = actions.onShowFileReferenceDetails
            )
        }

        // All message actions (branch navigation and future controls)
        Spacer(Modifier.height(8.dp))
        MessageActionRow(
            message = message,
            allMessagesMap = allMessagesMap,
            allRootMessageIds = allRootMessageIds,
            messageActions = actions,
            hovered = hovered,
            turnExecutionState = turnExecutionState,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp) // Reserve the height for the action row
        )
    }
}

/**
 * Renders the `"<author name>:"` header label of a message.
 *
 * Shared by the plain and the clickable header so both look identical, whether or not the label is wrapped in the
 * details tooltip.
 *
 * @param displayName Author name resolved for the message.
 * @param contentColor Base color of the surrounding bubble content.
 * @param modifier Modifier applied to the label, carrying the click action when the header offers one.
 */
@Composable
private fun MessageHeaderLabel(
    displayName: String,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = "$displayName:",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = contentColor.copy(alpha = 0.8f),
        modifier = modifier
    )
}

/**
 * Whether the header of an assistant message opens the message usage details dialog.
 *
 * A still-streaming placeholder is neither completed nor cause-bearing, so its header stays a plain label; a
 * generation that reached a terminal state is the one that has details and usage to describe.
 *
 * @receiver Assistant message whose header is being rendered.
 * @return Whether clicking the header should open the details dialog.
 */
private fun ChatMessage.AssistantMessage.offersUsageDetails(): Boolean = isComplete || incompleteCause != null