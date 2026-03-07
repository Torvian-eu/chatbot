package eu.torvian.chatbot.app.compose.preview

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.compose.chatarea.ChatArea
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaActions
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.tool.ToolCall
import org.jetbrains.compose.ui.tooling.preview.Preview
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
    val mockModel = LLMModel(
        id = 1L,
        name = "gpt-4",
        providerId = 1L,
        active = true,
        displayName = "GPT-4",
        type = LLMModelType.CHAT
    )

    ChatArea(
        state = ChatAreaState(
            sessionUiState = DataState.Success(mockChatSession),
            displayedMessages = mockChatSession.messages,
            availableModels = DataState.Success(listOf(mockModel)),
            availableSettingsForCurrentModel = DataState.Success(emptyList()),
            currentModel = mockModel,
            currentSettings = null,
            modelsById = mapOf(1L to mockModel)
        ),
        actions = object : ChatAreaActions {
            override fun onUpdateInput(newText: String) {}
            override fun onSendMessage() {}
            override fun onCancelSendMessage() {}
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
            override fun onSelectModel(modelId: Long?) {}
            override fun onSelectSettings(settingsId: Long?) {}
            override fun onRetryLoadingSession() {}
            override fun onShowToolConfig() {}
            override fun onShowToolCallDetails(toolCall: ToolCall) {}
            override fun onCopyMessage(message: ChatMessage) {}
            override fun onCopyThread() {}
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
