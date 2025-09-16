package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.LLMModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Displays the list of messages in a scrollable column.
 */
@Composable
fun MessageList(
    chatSession: ChatSession,
    displayedMessages: List<ChatMessage>,
    messageActions: MessageActions,
    editingMessage: ChatMessage?,
    editingContent: String?,
    actions: ChatAreaActions,
    modelsById: Map<Long, LLMModel> = emptyMap(),
    modifier: Modifier = Modifier.Companion
) {
    // Create LazyListState for scrollbar integration
    val lazyListState = rememberLazyListState()

    // State to track if we should be auto-scrolling
    var followTail by remember { mutableStateOf(true) }

    // Calculate the distance from the bottom
    val distanceFromBottom by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf 0
            if (lastVisibleItem.index != layoutInfo.totalItemsCount - 1)
                return@derivedStateOf 1000 // if not at the last item, return a large number
            val viewportEndOffset = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
            lastVisibleItem.offset + lastVisibleItem.size - viewportEndOffset
        }
    }

    // Effect to manage the 'followTail' state based on user scrolling
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect {
                followTail = distanceFromBottom == 0
            }
    }

    // Effect to manage the 'followTail' state based on distance from bottom
    LaunchedEffect(distanceFromBottom) {
        if (distanceFromBottom > 200) {
            followTail = false
        } else if (distanceFromBottom == 0) {
            followTail = true
        }
    }

    // The main auto-scroll effect
    LaunchedEffect(displayedMessages.size, displayedMessages.lastOrNull()?.content) {
        // Only scroll if we are following the tail and there are messages
        if (followTail && displayedMessages.isNotEmpty()) {
            // Scroll to the last item
            lazyListState.animateScrollToItem(lazyListState.layoutInfo.totalItemsCount)
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

    ScrollbarWrapper(
        listState = lazyListState,
        modifier = modifier
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(bottom = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = displayedMessages,
                key = { it.id } // Provide a key for efficient recomposition
            ) { message ->
                MessageItem(
                    message = message,
                    allMessagesMap = allMessagesMap,
                    allRootMessageIds = allRootMessageIds,
                    messageActions = messageActions,
                    editingMessage = editingMessage,
                    editingContent = editingContent,
                    actions = actions, // Pass actions for editing state access
                    modelsById = modelsById // Pass map for graceful degradation
                )
            }
        }
    }
}