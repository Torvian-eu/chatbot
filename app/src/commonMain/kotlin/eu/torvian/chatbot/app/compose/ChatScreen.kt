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
import eu.torvian.chatbot.common.models.core.FileReference
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.app.utils.misc.LruCache
import org.koin.compose.viewmodel.koinViewModel

/**
 * Maximum number of chat ViewModel slots kept alive at the same time.
 *
 * Slots are reused using LRU order when this limit is reached.
 */
private const val MAX_CHAT_VIEW_MODELS_IN_MEMORY = 20

/**
 * A stateful wrapper Composable for the main chat interface.
 * This component is responsible for:
 * - Obtaining ViewModels via Koin.
 * - Collecting necessary state from these ViewModels.
 * - Managing specific ViewModel interactions for the chat feature.
 * - Constructing and passing stateless UI state and action contracts to [ChatScreenContent].
 * (E7.S2: Implementing the Stateful part of the Screen with internal ViewModel management)
 *
 * Chat sessions are assigned to a bounded set of ViewModel slots using LRU. Recently used
 * sessions keep their own [ChatViewModel]; older sessions can be evicted and their slot reused,
 * keeping memory usage predictable while still supporting seamless switching for active sessions.
 *
 * @param sessionListViewModel The ViewModel managing the session list state.
 */
@Composable
fun ChatScreen(
    sessionListViewModel: SessionListViewModel = koinViewModel(),
    authState: AuthState
) {
    // Collect selected session first — its ID is used as the key for the ChatViewModel.
    val selectedSession by sessionListViewModel.selectedSession.collectAsState()
    val selectedSessionId = selectedSession?.id

    // Keeps track of which sessions currently occupy a limited pool of VM slots.
    val viewModelSlotAllocator = remember { ChatViewModelSlotAllocator(MAX_CHAT_VIEW_MODELS_IN_MEMORY) }
    // Stable key for Koin VM lookup; may be reused for a different session after LRU eviction.
    val chatViewModelKey = remember(selectedSessionId) {
        viewModelSlotAllocator.resolveViewModelKey(selectedSessionId)
    }

    // Obtain a session-scoped ChatViewModel keyed by LRU slot.
    // This keeps memory bounded while preserving state for recently used sessions.
    val chatViewModel: ChatViewModel = koinViewModel(key = chatViewModelKey)

    // --- Collect States for SessionListPanel ---
    val sessionListUiState by sessionListViewModel.listState.collectAsState()
    // selectedSession is already collected above
    val isCreatingNewGroup by sessionListViewModel.isCreatingNewGroup.collectAsState()
    val newGroupNameInput by sessionListViewModel.newGroupNameInput.collectAsState()
    val editingGroup by sessionListViewModel.editingGroup.collectAsState()
    val editingGroupNameInput by sessionListViewModel.editingGroupNameInput.collectAsState()
    val dialogState by sessionListViewModel.dialogState.collectAsState()

    // --- Collect States for ChatArea ---
    val chatSessionUiState by chatViewModel.sessionDataState.collectAsState()
    val availableModels by chatViewModel.availableModels.collectAsState()
    val availableSettings by chatViewModel.availableSettingsForCurrentModel.collectAsState()
    val currentModel by chatViewModel.currentModel.collectAsState()
    val currentSettings by chatViewModel.currentSettings.collectAsState()
    val modelsById by chatViewModel.modelsById.collectAsState()
    val chatInputContent by chatViewModel.inputContent.collectAsState()
    val chatReplyTargetMessage by chatViewModel.replyTargetMessage.collectAsState()
    val chatEditingMessage by chatViewModel.editingMessage.collectAsState()
    val chatEditingContent by chatViewModel.editingContent.collectAsState()
    val chatEditingFileReferences by chatViewModel.editingFileReferences.collectAsState()
    val chatEditingBasePathOverride by chatViewModel.editingBasePathOverride.collectAsState()
    val chatDisplayedMessages by chatViewModel.displayedMessages.collectAsState()
    val chatIsSendingMessage by chatViewModel.isSendingMessage.collectAsState()
    val chatDialogState by chatViewModel.dialogState.collectAsState()
    val enabledToolsForCurrentSession by chatViewModel.enabledToolsForCurrentSession.collectAsState()
    val toolCallsForCurrentSession by chatViewModel.toolCallsForCurrentSession.collectAsState()
    val pendingFileReferences by chatViewModel.pendingFileReferences.collectAsState()

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

    // --- Load Session on First Use ---
    // Load when this VM is fresh OR when an LRU slot is being reused for a different session.
    LaunchedEffect(chatViewModel, authState, selectedSessionId) {
        if (selectedSessionId != null
            && authState is AuthState.Authenticated
            && chatViewModel.activeSessionId.value != selectedSessionId
        ) {
            chatViewModel.loadSession(selectedSessionId, authState.userId)
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

            override fun onShowCloneSessionDialog(session: ChatSessionSummary) =
                sessionListViewModel.showCloneSessionDialog(session)

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
        chatEditingFileReferences, chatEditingBasePathOverride, chatDisplayedMessages, chatIsSendingMessage, chatDialogState, enabledToolsCount, toolCallsMap, pendingFileReferences
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
            editingFileReferences = chatEditingFileReferences,
            editingBasePathOverride = chatEditingBasePathOverride,
            displayedMessages = chatDisplayedMessages,
            isSendingMessage = chatIsSendingMessage,
            dialogState = chatDialogState,
            enabledToolsCount = enabledToolsCount,
            toolCallsMap = toolCallsMap,
            pendingFileReferences = pendingFileReferences
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
            override fun onAddFileReferences() =
                chatViewModel.pickAndAddFileReferences()
            override fun onRemoveFileReference(fileReference: FileReference) =
                chatViewModel.removeFileReference(fileReference)
            override fun onShowFileReferenceDetails(fileReference: FileReference) =
                chatViewModel.showFileReferenceDetails(fileReference)
            override fun onShowFileReferencesManagement() =
                chatViewModel.showFileReferencesManagement()

            override fun onAddEditingFileReferences() =
                chatViewModel.pickAndAddEditingFileReferences()
            override fun onRemoveEditingFileReference(fileReference: FileReference) =
                chatViewModel.removeEditingFileReference(fileReference)
            override fun onToggleEditingFileContent(fileReference: FileReference, includeContent: Boolean) =
                chatViewModel.toggleEditingFileContent(fileReference, includeContent)
            override fun onSetEditingBasePathOverride(path: String?) =
                chatViewModel.setEditingBasePathOverride(path)
            override fun onResetEditingBasePath() =
                chatViewModel.resetEditingBasePath()

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

/**
 * Maps session IDs to a bounded set of ViewModel slot indices.
 *
 * When all slots are occupied, the least-recently-used session mapping is replaced so its
 * slot can be reused by a newly selected session.
 *
 * @param maxViewModels Maximum number of slots that can exist concurrently.
 */
private class ChatViewModelSlotAllocator(
    maxViewModels: Int
) {
    /** Session -> slot mapping with access-order tracking. */
    private val sessionToSlot = LruCache<Long, Int>(maxViewModels)

    /** Pool of slots that have never been assigned yet. */
    private val freeSlots = (0 until maxViewModels).toMutableSet()

    /**
     * Resolves the Koin key for the selected session.
     *
     * @return A stable slot key (`chat_slot_<n>`) or `chat_none` when no session is selected.
     */
    fun resolveViewModelKey(sessionId: Long?): String {
        if (sessionId == null) return "chat_none"

        val assignedSlot = sessionToSlot.getOrPut(sessionId) {
            // Prefer an unused slot first.
            freeSlots.firstOrNull()
                .also { freeSlots.remove(it) }
                // If none are free, reuse the slot that belongs to the least recently used session.
                ?: sessionToSlot.leastRecentlyUsedValue
                ?: error("Expected an LRU session when no free slots are available")
        }
        return slotKey(assignedSlot)
    }

    private fun slotKey(slot: Int): String = "chat_slot_$slot"
}
