package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.torvian.chatbot.app.chat.search.SearchDirection
import eu.torvian.chatbot.app.compose.chatarea.ChatArea
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaActions
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.ToolCall
import kotlin.time.Instant

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
        agentRoleId = null,
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
    val mockModel = LLMModel(
        id = 1L,
        name = "gpt-4",
        providerId = 1L,
        active = true,
        displayName = "GPT-4"
    )

    ChatArea(
        state = ChatAreaState(
            sessionUiState = DataState.Success(mockChatSession),
            displayedMessages = mockChatSession.messages,
            modelsById = mapOf(1L to mockModel)
        ),
        actions = object : ChatAreaActions {
            override fun onUpdateInput(newText: String) {}
            override fun onSendMessage() {}
            override fun onCancelSendMessage() {}
            override fun onPauseSendMessage() {}
            override fun onStartReplyTo(message: ChatMessage) {}
            override fun onCancelReply() {}
            override fun onStartEditing(message: ChatMessage) {}
            override fun onUpdateEditingContent(newText: String) {}
            override fun onSaveEditing() {}
            override fun onSaveEditingAsCopy() {}
            override fun onCancelEditing() {}
            override fun onRequestDeleteMessage(message: ChatMessage) {}
            override fun onRequestDeleteThread(message: ChatMessage) {}
            override fun onRequestInsertMessage(message: ChatMessage) {}
            override fun onCancelDialog() {}
            override fun onSwitchBranchToMessage(messageId: Long) {}
            override fun onToggleMessageCollapsed(messageId: Long) {}
            override fun onSelectAgentRole(agentRoleId: Long?) {}
            override fun onRetryLoadRoles() {}
            override fun onAddRole() {}
            override fun onEditRole() {}
            override fun onRetryLoadingSession() {}
            override fun onShowToolCallDetails(toolCall: ToolCall) {}
            override fun onCopyMessage(message: ChatMessage) {}
            override fun onCopyThread() {}
            override fun onShowSearch() {}
            override fun onCloseSearch() {}
            override fun onUpdateSearchQuery(query: String) {}
            override fun onNavigateSearchResult(direction: SearchDirection) {}
            override fun onJumpToSearchResult(index: Int) {}
            override fun onBranchAndContinue(message: ChatMessage) {}
            override fun onRegenerateMessage(message: ChatMessage) {}
            override fun onAddFileReferences() {}
            override fun onRemoveFileReference(fileReference: FileReference) {}
            override fun onShowFileReferenceDetails(fileReference: FileReference) {}
            override fun onShowFileReferencesManagement() {}
            override fun onAddEditingFileReferences() {}
            override fun onRemoveEditingFileReference(fileReference: FileReference) {}
            override fun onToggleEditingFileContent(fileReference: FileReference, includeContent: Boolean) {}
            override fun onSetEditingBasePathOverride(path: String?) {}
            override fun onResetEditingBasePath() {}
        }
    )
}
