package eu.torvian.chatbot.app.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.torvian.chatbot.app.domain.contracts.ChatAreaActions
import eu.torvian.chatbot.app.domain.contracts.ChatAreaState
import eu.torvian.chatbot.app.domain.contracts.SessionListActions
import eu.torvian.chatbot.app.domain.contracts.SessionListState
import eu.torvian.chatbot.app.viewmodel.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.ChatMessage
import eu.torvian.chatbot.common.models.ChatSessionSummary
import org.koin.compose.viewmodel.koinViewModel

/**
 * A stateful wrapper Composable for the main chat interface.
 * This component is responsible for:
 * - Obtaining ViewModels via Koin.
 * - Collecting necessary state from these ViewModels.
 * - Managing specific ViewModel interactions for the chat feature.
 * - Constructing and passing stateless UI state and action contracts to [ChatScreenContent].
 * (E7.S2: Implementing the Stateful part of the Screen with internal ViewModel management)
 *
 * @param sessionListViewModel The ViewModel managing the session list state.
 * @param chatViewModel The ViewModel managing the active chat session state.
 */
@Composable
fun ChatScreen(
    sessionListViewModel: SessionListViewModel = koinViewModel(),
    chatViewModel: ChatViewModel = koinViewModel()
) {
    // --- Collect States for SessionListPanel ---
    val sessionListUiState by sessionListViewModel.listState.collectAsState()
    val selectedSessionId by sessionListViewModel.selectedSessionId.collectAsState()
    val isCreatingNewGroup by sessionListViewModel.isCreatingNewGroup.collectAsState()
    val newGroupNameInput by sessionListViewModel.newGroupNameInput.collectAsState()
    val editingGroup by sessionListViewModel.editingGroup.collectAsState()
    val editingGroupNameInput by sessionListViewModel.editingGroupNameInput.collectAsState()

    // --- Collect States for ChatArea ---
    val chatSessionUiState by chatViewModel.sessionState.collectAsState()
    val chatInputContent by chatViewModel.inputContent.collectAsState()
    val chatReplyTargetMessage by chatViewModel.replyTargetMessage.collectAsState()
    val chatEditingMessage by chatViewModel.editingMessage.collectAsState()
    val chatEditingContent by chatViewModel.editingContent.collectAsState()
    val chatCurrentBranchLeafId by chatViewModel.currentBranchLeafId.collectAsState()
    val chatDisplayedMessages by chatViewModel.displayedMessages.collectAsState()

    // --- SessionListPanel Contract Construction ---
    val sessionListPanelUiState = remember(
        sessionListUiState, selectedSessionId, isCreatingNewGroup,
        newGroupNameInput, editingGroup, editingGroupNameInput
    ) {
        SessionListState(
            listUiState = sessionListUiState,
            selectedSessionId = selectedSessionId,
            isCreatingNewGroup = isCreatingNewGroup,
            newGroupNameInput = newGroupNameInput,
            editingGroup = editingGroup,
            editingGroupNameInput = editingGroupNameInput
        )
    }
    val sessionListPanelActions = remember(sessionListViewModel) {
        object : SessionListActions {
            override fun onSessionSelected(sessionId: Long?) {
                sessionListViewModel.selectSession(sessionId)
                chatViewModel.loadSession(sessionId)
            }

            override fun onCreateNewSession(name: String?) = sessionListViewModel.createNewSession(name)
            override fun onRenameSession(session: ChatSessionSummary, newName: String) =
                sessionListViewModel.renameSession(session, newName)

            override fun onDeleteSession(sessionId: Long) = sessionListViewModel.deleteSession(sessionId)
            override fun onAssignSessionToGroup(sessionId: Long, groupId: Long?) =
                sessionListViewModel.assignSessionToGroup(sessionId, groupId)

            override fun onStartCreatingNewGroup() = sessionListViewModel.startCreatingNewGroup()
            override fun onUpdateNewGroupNameInput(newText: String) =
                sessionListViewModel.updateNewGroupNameInput(newText)

            override fun onCreateNewGroup() = sessionListViewModel.createNewGroup()
            override fun onCancelCreatingNewGroup() = sessionListViewModel.cancelCreatingNewGroup()
            override fun onStartRenamingGroup(group: ChatGroup) = sessionListViewModel.startRenamingGroup(group)
            override fun onUpdateEditingGroupNameInput(newText: String) =
                sessionListViewModel.updateEditingGroupNameInput(newText)

            override fun onSaveRenamedGroup() = sessionListViewModel.saveRenamedGroup()
            override fun onCancelRenamingGroup() = sessionListViewModel.cancelRenamingGroup()
            override fun onDeleteGroup(groupId: Long) = sessionListViewModel.deleteGroup(groupId)
            override fun onRetryLoadingSessions() = sessionListViewModel.loadSessionsAndGroups()
        }
    }

    // --- ChatArea Contract Construction ---
    val chatAreaState = remember(
        chatSessionUiState, chatInputContent, chatReplyTargetMessage,
        chatEditingMessage, chatEditingContent, chatCurrentBranchLeafId, chatDisplayedMessages
    ) {
        ChatAreaState(
            sessionUiState = chatSessionUiState,
            inputContent = chatInputContent,
            replyTargetMessage = chatReplyTargetMessage,
            editingMessage = chatEditingMessage,
            editingContent = chatEditingContent,
            currentBranchLeafId = chatCurrentBranchLeafId,
            displayedMessages = chatDisplayedMessages
        )
    }
    val chatAreaActions = remember(chatViewModel, selectedSessionId) {
        object : ChatAreaActions {
            override fun onUpdateInput(newText: String) = chatViewModel.updateInput(newText)
            override fun onSendMessage() = chatViewModel.sendMessage()
            override fun onStartReplyTo(message: ChatMessage) = chatViewModel.startReplyTo(message)
            override fun onCancelReply() = chatViewModel.cancelReply()
            override fun onStartEditing(message: ChatMessage) = chatViewModel.startEditing(message)
            override fun onUpdateEditingContent(newText: String) = chatViewModel.updateEditingContent(newText)
            override fun onSaveEditing() = chatViewModel.saveEditing()
            override fun onCancelEditing() = chatViewModel.cancelEditing()
            override fun onDeleteMessage(messageId: Long) = chatViewModel.deleteMessage(messageId)
            override fun onSwitchBranchToMessage(messageId: Long) = chatViewModel.switchBranchToMessage(messageId)
            override fun onSelectModel(modelId: Long?) = chatViewModel.selectModel(modelId)
            override fun onSelectSettings(settingsId: Long?) = chatViewModel.selectSettings(settingsId)
            override fun onRetryLoadingSession() {
                selectedSessionId?.let { sessionId ->
                    chatViewModel.loadSession(sessionId, forceReload = true)
                }
            }
        }
    }

    // Pass all collected states and actions to the stateless ChatScreenContent
    ChatScreenContent(
        sessionListState = sessionListPanelUiState,
        sessionListActions = sessionListPanelActions,
        chatAreaState = chatAreaState,
        chatAreaActions = chatAreaActions
    )
}
