package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ScrollbarWrapper
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import eu.torvian.chatbot.common.models.LLMModel

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