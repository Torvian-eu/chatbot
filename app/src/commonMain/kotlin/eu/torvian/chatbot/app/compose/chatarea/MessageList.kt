package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Displays the list of messages in a scrollable column.
 *
 * @param chatSession Full chat session, used to derive branch navigation context.
 * @param displayedMessages Messages currently visible in the active branch.
 * @param collapsedMessageIds IDs of messages currently rendered in collapsed mode.
 * @param messageActions Action bundle for message item controls.
 * @param inputAreaActions Action bundle for inline expanded input areas.
 * @param editingMessage Message currently being edited, if any.
 * @param editingContent Current edit content for [editingMessage].
 * @param editingFileReferences File references attached to the message being edited.
 * @param editingBasePathOverride Optional editing base path override.
 * @param modelsById Optional model lookup used for assistant label rendering.
 * @param toolCallsMap Tool calls grouped by message ID.
 * @param isInputExpanded Whether the input is rendered inline within the message list.
 * @param scrollToInputTrigger Incrementing trigger used to request scrolling to inline input.
 * @param inputContent Current input content.
 * @param replyTargetMessage Current reply target, if replying.
 * @param isSendingMessage Whether a send/stream operation is active.
 * @param pendingFileReferences File references attached to the composer.
 * @param modifier Modifier applied to the outer container.
 */
@Composable
fun MessageList(
    chatSession: ChatSession,
    displayedMessages: List<ChatMessage>,
    collapsedMessageIds: Set<Long>,
    messageActions: MessageActions,
    inputAreaActions: InputAreaActions,
    editingMessage: ChatMessage?,
    editingContent: String?,
    editingFileReferences: List<FileReference>,
    editingBasePathOverride: String?,
    modelsById: Map<Long, LLMModel> = emptyMap(),
    toolCallsMap: Map<Long, List<ToolCall>> = emptyMap(),
    isInputExpanded: Boolean = false,
    scrollToInputTrigger: Int = 0,
    inputContent: String = "",
    replyTargetMessage: ChatMessage? = null,
    isSendingMessage: Boolean = false,
    pendingFileReferences: List<FileReference> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Create ScrollState for scrollbar integration
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Requesters for bringing input areas into view
    val inlineReplyInputRequester = remember { BringIntoViewRequester() }
    val trailingInputRequester = remember { BringIntoViewRequester() }

    // State to track if we should be auto-scrolling
    var followTail by remember { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Intercept branch switches at the UI layer before delegating to MessageActions.
    val interceptedMessageItemActions = remember(messageActions) {
        messageActions.copy(
            onSwitchBranchToMessage = { messageId ->
                followTail = false
                messageActions.onSwitchBranchToMessage(messageId)
            }
        )
    }

    // Helper to run a scroll operation while temporarily disabling user sync.
    suspend fun runProgrammaticScroll(block: suspend () -> Unit) {
        isProgrammaticScroll = true
        try {
            block()
        } finally {
            // Allow pending layout/scroll updates to settle before user-sync resumes.
            yield()
            isProgrammaticScroll = false
        }
    }

    // Threshold for considering a message "long" (collapsible)
    val collapseThreshold = 500

    // Check whether the list is scrolled to the bottom.
    val isAtBottom by remember {
        derivedStateOf {
            val bottomThreshold = (scrollState.maxValue - 8).coerceAtLeast(0)
            scrollState.value >= bottomThreshold
        }
    }

    // Keep followTail in sync with user-visible position changes.
    // Streaming content can increase maxValue without user scrolling, which should not disable followTail.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }
            .distinctUntilChanged()
            .collect {
                if (!isProgrammaticScroll) {
                    followTail = isAtBottom
                }
            }
    }

    // Auto-scroll to bottom for regular streaming/appended messages while follow-tail is enabled.
    LaunchedEffect(displayedMessages.size, displayedMessages.lastOrNull()?.content, followTail) {
        if (followTail && displayedMessages.isNotEmpty()) {
            runProgrammaticScroll { scrollState.scrollTo(scrollState.maxValue) }
        }
    }

    // Scroll to expanded input area when manually expanded
    LaunchedEffect(scrollToInputTrigger, replyTargetMessage?.id, isInputExpanded) {
        if (scrollToInputTrigger > 0 && isInputExpanded) {
            // If replying, scroll to the reply target message position
            if (replyTargetMessage != null) {
                if (displayedMessages.any { it.id == replyTargetMessage.id }) {
                    runProgrammaticScroll {
                        inlineReplyInputRequester.bringIntoView()
                    }
                }
            } else {
                // Otherwise, scroll to the last item (expanded input at end)
                runProgrammaticScroll {
                    trailingInputRequester.bringIntoView()
                }
            }
        }
    }

    // Calculate allMessagesMap and allRootMessageIds from the chat session
    val allMessagesMap = remember(chatSession.messages) {
        chatSession.messages.associateBy { it.id }
    }
    val allRootMessageIds = remember(chatSession.messages) {
        chatSession.messages.filter { it.parentMessageId == null }
            .sortedBy { it.createdAt } // Ensure consistent order for roots
            .map { it.id }
    }

    Box(modifier = modifier) {
        ScrollbarWrapper(
            scrollState = scrollState,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                displayedMessages.forEach { message ->
                    key(message.id) {
                        MessageItem(
                            message = message,
                            allMessagesMap = allMessagesMap,
                            allRootMessageIds = allRootMessageIds,
                            actions = interceptedMessageItemActions,
                            editingMessage = editingMessage,
                            editingContent = editingContent,
                            editingFileReferences = editingFileReferences,
                            editingBasePathOverride = editingBasePathOverride,
                            modelsById = modelsById, // Pass map for graceful degradation
                            toolCallsForMessage = toolCallsMap[message.id] ?: emptyList(),
                            isCollapsed = message.id in collapsedMessageIds,
                            isCollapsible = message.content.length > collapseThreshold
                        )

                        // Insert expanded input area right after reply target message
                        if (isInputExpanded && replyTargetMessage != null && message.id == replyTargetMessage.id) {
                            InputArea(
                                inputContent = inputContent,
                                actions = inputAreaActions,
                                replyTargetMessage = replyTargetMessage,
                                isSendingMessage = isSendingMessage,
                                isExpanded = true,
                                fileReferences = pendingFileReferences,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(inlineReplyInputRequester)
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                // Add expanded input area as last item when expanded and NOT replying
                if (isInputExpanded && replyTargetMessage == null) {
                    key("expanded_input_area") {
                        InputArea(
                            inputContent = inputContent,
                            actions = inputAreaActions,
                            replyTargetMessage = replyTargetMessage,
                            isSendingMessage = isSendingMessage,
                            isExpanded = true,
                            fileReferences = pendingFileReferences,
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(trailingInputRequester)
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // Scroll to bottom button - only visible when user has scrolled up
        AnimatedVisibility(
            visible = !isAtBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        runProgrammaticScroll {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                        followTail = true
                    }
                },
                modifier = Modifier.size(40.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Scroll to bottom"
                )
            }
        }
    }
}