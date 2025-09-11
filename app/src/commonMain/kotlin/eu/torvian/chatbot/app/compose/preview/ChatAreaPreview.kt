package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.compose.chatarea.ChatArea
import eu.torvian.chatbot.app.domain.contracts.ChatAreaActions
import eu.torvian.chatbot.app.domain.contracts.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatSessionData
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSession
import kotlinx.datetime.Instant
import org.jetbrains.compose.ui.tooling.preview.Preview

@Preview
@Composable
fun ChatAreaPreview() {
    // Mock data for preview
    val mockChatSession = ChatSession(
        id = 1L,
        name = "Preview Session",
        createdAt = Instant.fromEpochMilliseconds(1234567890000L),
        updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
        groupId = null,
        currentModelId = null,
        currentSettingsId = null,
        currentLeafMessageId = 2L,
        messages = listOf(
            ChatMessage.UserMessage(
                id = 1L,
                sessionId = 1L,
                content = "Hello, how are you?",
                createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                parentMessageId = null,
                childrenMessageIds = listOf(2L)
            ),
            ChatMessage.AssistantMessage(
                id = 2L,
                sessionId = 1L,
                content = "I'm doing well, thank you!",
                createdAt = Instant.fromEpochMilliseconds(1234567890000L),
                updatedAt = Instant.fromEpochMilliseconds(1234567890000L),
                parentMessageId = 1L,
                childrenMessageIds = emptyList(),
                modelId = null,
                settingsId = null
            )
        )
    )
    ChatArea(
        state = ChatAreaState(
            sessionUiState = DataState.Success(ChatSessionData(session = mockChatSession)),
            displayedMessages = mockChatSession.messages
        ),
        actions = object : ChatAreaActions {
            override fun onUpdateInput(newText: String) {}
            override fun onSendMessage() {}
            override fun onStartReplyTo(message: ChatMessage) {}
            override fun onCancelReply() {}
            override fun onStartEditing(message: ChatMessage) {}
            override fun onUpdateEditingContent(newText: String) {}
            override fun onSaveEditing() {}
            override fun onCancelEditing() {}
            override fun onRequestDeleteMessage(message: ChatMessage) {}
            override fun onCancelDialog() {}
            override fun onSwitchBranchToMessage(messageId: Long) {}
            override fun onSelectModel(modelId: Long?) {}
            override fun onSelectSettings(settingsId: Long?) {}
            override fun onRetryLoadingSession() {}
        }
    )
}
