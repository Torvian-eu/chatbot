package eu.torvian.chatbot.app.compose

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaActions
import eu.torvian.chatbot.app.compose.chatarea.ChatAreaState
import eu.torvian.chatbot.app.compose.chatarea.ChatTopBarContent
import eu.torvian.chatbot.app.compose.sessionlist.SessionListActions
import eu.torvian.chatbot.app.compose.sessionlist.SessionListState
import eu.torvian.chatbot.app.compose.topbar.TopBarContentProvider
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSessionSummary
import eu.torvian.chatbot.common.models.tool.ToolCall
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
    chatViewModel: ChatViewModel = koinViewModel(),
    authState: AuthState
) {
    // --- Collect States for SessionListPanel ---
    val sessionListUiState by sessionListViewModel.listState.collectAsState()
    val selectedSession by sessionListViewModel.selectedSession.collectAsState()
    val isCreatingNewGroup by sessionListViewModel.isCreatingNewGroup.collectAsState()
    val newGroupNameInput by sessionListViewModel.newGroupNameInput.collectAsState()
    val editingGroup by sessionListViewModel.editingGroup.collectAsState()
    val editingGroupNameInput by sessionListViewModel.editingGroupNameInput.collectAsState()
    val dialogState by sessionListViewModel.dialogState.collectAsState()

    // --- Collect States for ChatArea ---
    val chatSessionUiState by chatViewModel.sessionDataState.collectAsState()
    val activeSessionId by chatViewModel.activeSessionId.collectAsState() // Collect active ID for comparison
    val availableModels by chatViewModel.availableModels.collectAsState()
    val availableSettings by chatViewModel.availableSettingsForCurrentModel.collectAsState()
    val currentModel by chatViewModel.currentModel.collectAsState()
    val currentSettings by chatViewModel.currentSettings.collectAsState()
    val modelsById by chatViewModel.modelsById.collectAsState()
    val chatInputContent by chatViewModel.inputContent.collectAsState()
    val chatReplyTargetMessage by chatViewModel.replyTargetMessage.collectAsState()
    val chatEditingMessage by chatViewModel.editingMessage.collectAsState()
    val chatEditingContent by chatViewModel.editingContent.collectAsState()
    val chatDisplayedMessages by chatViewModel.displayedMessages.collectAsState()
    val chatIsSendingMessage by chatViewModel.isSendingMessage.collectAsState()
    val chatDialogState by chatViewModel.dialogState.collectAsState()
    val enabledToolsForCurrentSession by chatViewModel.enabledToolsForCurrentSession.collectAsState()
    val toolCallsForCurrentSession by chatViewModel.toolCallsForCurrentSession.collectAsState()

    // Derive enabled tools count
    val enabledToolsCount = enabledToolsForCurrentSession.dataOrNull?.size ?: 0

    // Derive tool calls map
    val toolCallsMap = toolCallsForCurrentSession.dataOrNull ?: emptyMap()

    // --- Local UI State for Session List Panel Collapse ---
    var isSessionListCollapsed by rememberSaveable { mutableStateOf(false) }

    // Set top bar content when this screen is active using the new provider
    TopBarContentProvider(
        content = { userMenu, navItems ->
            ChatTopBarContent(
                userMenu = userMenu,
                navItems = navItems,
                currentModel = currentModel,
                currentSettings = currentSettings,
                availableModels = availableModels,
                availableSettings = availableSettings,
                onSelectModel = { chatViewModel.selectModel(it) },
                onSelectSettings = { chatViewModel.selectSettings(it) },
                onRetryLoadModels = { /* TODO: wire up retry */ },
                onRetryLoadSettings = { /* TODO: wire up retry */ },
                onShowToolConfig = { chatViewModel.showToolConfigDialog() },
                enabledToolsCount = enabledToolsCount,
                isSessionListCollapsed = isSessionListCollapsed,
                onToggleSessionList = { isSessionListCollapsed = !isSessionListCollapsed },
                onCopyThread = { chatViewModel.copyThreadToClipboard() }
            )
        }
    )

    // --- State-Driven Effect to Load Sessions ---
    LaunchedEffect(selectedSession, authState) {
        val newSessionId = selectedSession?.id
        if (newSessionId != null && newSessionId != activeSessionId) {
            if (authState is AuthState.Authenticated) {
                chatViewModel.loadSession(newSessionId, authState.userId)
            } else {
                // Not authenticated - clear session to avoid loading user-specific data
                chatViewModel.clearSession()
            }
        } else if (newSessionId == null && activeSessionId != null) {
            chatViewModel.clearSession()
        }
    }

    // --- SessionListPanel Contract Construction ---
    val sessionListPanelUiState = remember(
        sessionListUiState, selectedSession, isCreatingNewGroup,
        newGroupNameInput, editingGroup, editingGroupNameInput, dialogState
    ) {
        SessionListState(
            listUiState = sessionListUiState,
            selectedSessionId = selectedSession?.id,
            isCreatingNewGroup = isCreatingNewGroup,
            newGroupNameInput = newGroupNameInput,
            editingGroup = editingGroup,
            editingGroupNameInput = editingGroupNameInput,
            dialogState = dialogState
        )
    }
    val sessionListPanelActions = remember(sessionListViewModel) {
        object : SessionListActions {
            override fun onSessionSelected(sessionId: Long?) = sessionListViewModel.selectSession(sessionId)
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
            override fun onRetryLoadingSessions() = sessionListViewModel.loadSessionsAndGroups()

            // Dialog management actions
            override fun onShowNewSessionDialog() = sessionListViewModel.showNewSessionDialog()
            override fun onShowRenameSessionDialog(session: ChatSessionSummary) =
                sessionListViewModel.showRenameSessionDialog(session)

            override fun onShowDeleteSessionDialog(sessionId: Long) =
                sessionListViewModel.showDeleteSessionDialog(sessionId)

            override fun onShowAssignGroupDialog(session: ChatSessionSummary) =
                sessionListViewModel.showAssignGroupDialog(session)

            override fun onShowDeleteGroupDialog(groupId: Long) =
                sessionListViewModel.showDeleteGroupDialog(groupId)
        }
    }

    // --- ChatArea Contract Construction ---
    val chatAreaState = remember(
        chatSessionUiState, availableModels, availableSettings, currentModel, currentSettings, modelsById,
        chatInputContent, chatReplyTargetMessage, chatEditingMessage, chatEditingContent,
        chatDisplayedMessages, chatIsSendingMessage, chatDialogState, enabledToolsCount, toolCallsMap
    ) {
        ChatAreaState(
            sessionUiState = chatSessionUiState,
            availableModels = availableModels,
            availableSettingsForCurrentModel = availableSettings,
            currentModel = currentModel,
            currentSettings = currentSettings,
            modelsById = modelsById,
            inputContent = chatInputContent,
            replyTargetMessage = chatReplyTargetMessage,
            editingMessage = chatEditingMessage,
            editingContent = chatEditingContent,
            displayedMessages = chatDisplayedMessages,
            isSendingMessage = chatIsSendingMessage,
            dialogState = chatDialogState,
            enabledToolsCount = enabledToolsCount,
            toolCallsMap = toolCallsMap
        )
    }
    val chatAreaActions = remember(chatViewModel, selectedSession) {
        object : ChatAreaActions {
            override fun onUpdateInput(newText: String) = chatViewModel.updateInput(newText)
            override fun onSendMessage() = chatViewModel.sendMessage()
            override fun onCancelSendMessage() = chatViewModel.cancelSendMessage()
            override fun onStartReplyTo(message: ChatMessage) = chatViewModel.startReplyTo(message)
            override fun onCancelReply() = chatViewModel.cancelReply()
            override fun onStartEditing(message: ChatMessage) = chatViewModel.startEditing(message)
            override fun onUpdateEditingContent(newText: String) = chatViewModel.updateEditingContent(newText)
            override fun onSaveEditing() = chatViewModel.saveEditing()
            override fun onSaveEditingAsCopy() = chatViewModel.saveEditingAsCopy()
            override fun onCancelEditing() = chatViewModel.cancelEditing()
            override fun onRequestDeleteMessage(message: ChatMessage) = chatViewModel.requestDeleteMessage(message)
            override fun onRequestDeleteThread(message: ChatMessage) = chatViewModel.requestDeleteMessageRecursively(message)
            override fun onRequestInsertMessage(message: ChatMessage) = chatViewModel.onRequestInsertMessage(message)
            override fun onCancelDialog() = chatViewModel.cancelDialog()
            override fun onSwitchBranchToMessage(messageId: Long) = chatViewModel.switchBranchToMessage(messageId)
            override fun onSelectModel(modelId: Long?) = chatViewModel.selectModel(modelId)
            override fun onSelectSettings(settingsId: Long?) = chatViewModel.selectSettings(settingsId)
            override fun onShowToolConfig() = chatViewModel.showToolConfigDialog()
            override fun onShowToolCallDetails(toolCall: ToolCall) =
                chatViewModel.showToolCallDetails(toolCall)
            override fun onCopyMessage(message: ChatMessage) =
                chatViewModel.copyMessageToClipboard(message)
            override fun onCopyThread() =
                chatViewModel.copyThreadToClipboard()
            override fun onBranchAndContinue(message: ChatMessage) =
                chatViewModel.sendMessage(continueFromMessage = message)
            override fun onRegenerateMessage(message: ChatMessage) =
                chatViewModel.regenerateMessage(message)

            override fun onRetryLoadingSession() {
                selectedSession?.id?.let { sessionId ->
                    if (authState is AuthState.Authenticated) {
                        chatViewModel.loadSession(sessionId, authState.userId)
                    }
                }
            }
        }
    }

    // Pass all collected states and actions to the stateless ChatScreenContent
    ChatScreenContent(
        sessionListState = sessionListPanelUiState,
        sessionListActions = sessionListPanelActions,
        chatAreaState = chatAreaState,
        chatAreaActions = chatAreaActions,
        isSessionListCollapsed = isSessionListCollapsed
    )
}
