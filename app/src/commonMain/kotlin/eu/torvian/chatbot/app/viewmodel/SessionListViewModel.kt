package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.Either
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.SessionListData
import eu.torvian.chatbot.app.domain.contracts.SessionListDialogState
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_sessions_groups
import eu.torvian.chatbot.app.repository.GroupRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.ErrorNotifier
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.common.models.api.core.CreateGroupRequest
import eu.torvian.chatbot.common.models.api.core.CreateSessionRequest
import eu.torvian.chatbot.common.models.api.core.RenameGroupRequest
import eu.torvian.chatbot.common.models.api.core.UpdateSessionGroupRequest
import eu.torvian.chatbot.common.models.api.core.UpdateSessionNameRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state for the chat session list panel,
 * using KMP ViewModel, StateFlow, and DataState for loading states.
 *
 * This class is responsible for:
 * - Loading and holding the state (Idle/Loading/Success/Error) of the list of all chat sessions and groups.
 * - Structuring the session list visually by group ("Ungrouped" and defined groups).
 * - Managing the currently selected session ID.
 * - Handling user actions like creating, deleting, renaming sessions, and assigning sessions to groups.
 * - Handling user actions for managing groups (creating, renaming, deleting).
 * - Communicating with the backend via the SessionRepository and GroupRepository.
 *
 * @constructor
 * @param sessionRepository The repository for session-related operations.
 * @param groupRepository The repository for group-related operations.
 * @param eventBus The event bus for emitting global events.
 * @param errorNotifier The error notifier for handling repository errors.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 *
 * @property listState The state of the chat session list and groups.
 * @property selectedSession The currently selected session.
 * @property isCreatingNewGroup UI state indicating if the new group input field is visible.
 * @property newGroupNameInput Content of the new group input field.
 * @property editingGroup The group currently being edited/renamed. Null if none.
 * @property editingGroupNameInput Content of the editing group name input field.
 * @property dialogState The current dialog state for the session list panel.
 */
class SessionListViewModel(
    private val sessionRepository: SessionRepository,
    private val groupRepository: GroupRepository,
    private val eventBus: EventBus,
    private val errorNotifier: ErrorNotifier,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<SessionListViewModel>()
    }

    // --- Private State Properties ---

    /**
     * The ID of the session the user has explicitly selected.
     */
    private val userSelectedSessionId = MutableStateFlow<Long?>(null)

    /**
     * The event ID of the last failed load operation.
     * Used for retry functionality.
     */
    private val _lastFailedLoadEventId = MutableStateFlow<String?>(null)
    private val _isCreatingNewGroup = MutableStateFlow(false)
    private val _newGroupNameInput = MutableStateFlow("")
    private val _editingGroup = MutableStateFlow<ChatGroup?>(null)
    private val _editingGroupNameInput = MutableStateFlow("")
    private val _dialogState = MutableStateFlow<SessionListDialogState>(SessionListDialogState.None)

    // --- Public State Properties ---

    /**
     * The state of the chat session list and groups.
     */
    val listState: StateFlow<DataState<RepositoryError, SessionListData>> = combine(
        sessionRepository.sessions,
        groupRepository.groups
    ) { sessionsDataState, groupsDataState ->
        when {
            sessionsDataState is DataState.Loading || groupsDataState is DataState.Loading -> DataState.Loading
            sessionsDataState is DataState.Error -> DataState.Error(sessionsDataState.error)
            groupsDataState is DataState.Error -> DataState.Error(groupsDataState.error)
            sessionsDataState is DataState.Success && groupsDataState is DataState.Success -> {
                DataState.Success(
                    SessionListData(
                        allSessions = sessionsDataState.data,
                        allGroups = groupsDataState.data
                    )
                )
            }

            else -> DataState.Idle
        }
    }.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(), initialValue = DataState.Idle)

    /**
     * The currently selected session.
     * This should be observed by the main chat UI to load the session details.
     */
    val selectedSession: StateFlow<ChatSessionSummary?> = combine(
        listState.map { it.dataOrNull?.allSessions },
        userSelectedSessionId
    ) { sessionsList, currentSelectedId ->
        sessionsList?.find { it.id == currentSelectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /**
     * UI state indicating if the new group input field is visible.
     */
    val isCreatingNewGroup: StateFlow<Boolean> = _isCreatingNewGroup.asStateFlow()

    /**
     * Content of the new group input field.
     */
    val newGroupNameInput: StateFlow<String> = _newGroupNameInput.asStateFlow()

    /**
     * The group currently being edited/renamed. Null if none.
     */
    val editingGroup: StateFlow<ChatGroup?> = _editingGroup.asStateFlow()

    /**
     * Content of the editing group name input field.
     */
    val editingGroupNameInput: StateFlow<String> = _editingGroupNameInput.asStateFlow()

    /**
     * The current dialog state for the session list panel.
     * Manages which dialog (if any) should be displayed and contains dialog-specific form state.
     */
    val dialogState: StateFlow<SessionListDialogState> = _dialogState.asStateFlow()

    // --- Initialization ---

    init {
        // Handle retry functionality via EventBus
        eventBus.events
            .filterIsInstance<SnackbarInteractionEvent>()
            .combine(_lastFailedLoadEventId) { event, lastFailedEventId ->
                event to lastFailedEventId
            }
            .filter { (event, lastFailedEventId) -> event.originalAppEventId == lastFailedEventId }
            .onEach { (event, _) ->
                if (event.isActionPerformed) {
                    logger.info("Retrying loadSessionsAndGroups due to Snackbar action!")
                    _lastFailedLoadEventId.value = null // Clear ID before retrying
                    loadSessionsAndGroups() // Trigger retry
                } else { // It was dismissed (by user or timeout)
                    logger.info("Snackbar dismissed, not retrying loadSessionsAndGroups.")
                    _lastFailedLoadEventId.value = null
                }
            }
            .launchIn(viewModelScope)
    }

    // --- Public Action Functions (Called by UI Components) ---

    /**
     * Loads all session summaries and groups from the backend (E2.S3, E6.S4).
     * Uses parZip for concurrent loading via repositories.
     */
    fun loadSessionsAndGroups() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { sessionRepository.loadSessions() },
                { groupRepository.loadGroups() }
            ) { sessionsResult, groupsResult ->
                // Handle any errors from the repository operations
                val error = when {
                    sessionsResult is Either.Left -> sessionsResult.value
                    groupsResult is Either.Left -> groupsResult.value
                    else -> null
                }
                if (error != null) {
                    val eventId = errorNotifier.repositoryError(
                        error = error,
                        shortMessageRes = Res.string.error_loading_sessions_groups,
                        isRetryable = true
                    )
                    _lastFailedLoadEventId.value = eventId
                } else {
                    // Clear any stored error ID if load was successful
                    _lastFailedLoadEventId.value = null
                }
            }
        }
    }

    /**
     * Selects a chat session from the list, updating the [selectedSession].
     * (E2.S4)
     *
     * @param sessionId The ID of the session to select, or null to clear selection.
     */
    fun selectSession(sessionId: Long?) {
        userSelectedSessionId.value = sessionId
    }

    /**
     * Updates the content of the new group name input field.
     */
    fun updateNewGroupNameInput(newText: String) {
        _newGroupNameInput.value = newText
    }

    /**
     * Starts the process of creating a new group (E6.S3).
     */
    fun startCreatingNewGroup() {
        _isCreatingNewGroup.value = true
        _newGroupNameInput.value = ""
    }

    /**
     * Cancels the new group creation process.
     */
    fun cancelCreatingNewGroup() {
        _isCreatingNewGroup.value = false
        _newGroupNameInput.value = ""
    }

    /**
     * Creates a new group with the current name from the input field (E6.S3).
     */
    fun createNewGroup() {
        val name = _newGroupNameInput.value.trim()
        if (name.isBlank()) {
            // Show inline validation error or update state
            println("Validation Error: Group name cannot be empty.")
            return
        }

        viewModelScope.launch(uiDispatcher) {
            groupRepository.createGroup(CreateGroupRequest(name))
                .fold(
                    ifLeft = { repositoryError ->
                        errorNotifier.repositoryError(
                            error = repositoryError,
                            shortMessage = "Failed to create new group"
                        )
                    },
                    ifRight = {
                        cancelCreatingNewGroup() // Hide input on success
                    }
                )
        }
    }

    /**
     * Starts the process of renaming a group (E6.S5).
     *
     * @param group The group to rename.
     */
    fun startRenamingGroup(group: ChatGroup) {
        _editingGroup.value = group
        _editingGroupNameInput.value = group.name
    }

    /**
     * Cancels the group renaming process.
     */
    fun cancelRenamingGroup() {
        _editingGroup.value = null
        _editingGroupNameInput.value = ""
    }

    /**
     * Updates the content of the editing group name input field.
     */
    fun updateEditingGroupNameInput(newText: String) {
        _editingGroupNameInput.value = newText
    }

    /**
     * Saves the renamed group name (E6.S5).
     */
    fun saveRenamedGroup() {
        val groupToRename = _editingGroup.value ?: return
        val newName = _editingGroupNameInput.value.trim()
        if (newName.isBlank()) {
            // Show inline validation error or update state
            println("Validation Error: Group name cannot be empty.")
            return
        }
        viewModelScope.launch(uiDispatcher) {
            groupRepository.renameGroup(groupToRename.id, RenameGroupRequest(newName))
                .fold(
                    ifLeft = { repositoryError ->
                        errorNotifier.repositoryError(
                            error = repositoryError,
                            shortMessage = "Failed to rename group"
                        )
                    },
                    ifRight = {
                        cancelRenamingGroup() // Hide input on success
                    }
                )
        }
    }

    /**
     * Shows the new session dialog with pre-bound actions.
     */
    fun showNewSessionDialog() {
        _dialogState.value = SessionListDialogState.NewSession(
            sessionNameInput = "",
            onNameInputChange = { name -> updateDialogSessionName(name) },
            onCreateSession = { sessionName ->
                createNewSession(sessionName)
            },
            onDismiss = { cancelDialog() }
        )
    }

    /**
     * Shows the rename session dialog with pre-bound actions.
     *
     * @param session The session to rename.
     */
    fun showRenameSessionDialog(session: ChatSessionSummary) {
        _dialogState.value = SessionListDialogState.RenameSession(
            session = session,
            newSessionNameInput = session.name,
            onNameInputChange = { name -> updateDialogSessionName(name) },
            onRenameSession = { newName ->
                renameSession(session, newName)
            },
            onDismiss = { cancelDialog() }
        )
    }

    /**
     * Shows the delete session dialog with pre-bound actions.
     *
     * @param sessionId The ID of the session to delete.
     */
    fun showDeleteSessionDialog(sessionId: Long) {
        _dialogState.value = SessionListDialogState.DeleteSession(
            sessionId = sessionId,
            onDeleteConfirm = {
                deleteSession(sessionId)
            },
            onDismiss = { cancelDialog() }
        )
    }

    /**
     * Shows the assign group dialog with pre-bound actions.
     *
     * @param session The session to assign to a group.
     */
    fun showAssignGroupDialog(session: ChatSessionSummary) {
        _dialogState.value = SessionListDialogState.AssignGroup(
            sessionId = session.id,
            groupId = session.groupId,
            onAssignToGroup = { sessionId, groupId ->
                assignSessionToGroup(sessionId, groupId)
            },
            onDismiss = { cancelDialog() }
        )
    }

    /**
     * Shows the delete group dialog with pre-bound actions.
     *
     * @param groupId The ID of the group to delete.
     */
    fun showDeleteGroupDialog(groupId: Long) {
        _dialogState.value = SessionListDialogState.DeleteGroup(
            groupId = groupId,
            onDeleteConfirm = {
                deleteGroup(groupId)
            },
            onDismiss = { cancelDialog() }
        )
    }

    /**
     * Cancels/closes any dialog by setting state to None.
     */
    fun cancelDialog() {
        _dialogState.value = SessionListDialogState.None
    }

    // --- Private Helper Functions (Internal Logic) ---

    /**
     * Creates a new chat session (E2.S1).
     *
     * @param initialName Optional initial name.
     */
    private fun createNewSession(initialName: String? = null) {
        // If initialName is blank, pass null to use default name
        val sanitizedName = initialName?.ifBlank { null }

        viewModelScope.launch(uiDispatcher) {
            sessionRepository.createSession(CreateSessionRequest(name = sanitizedName))
                .fold(
                    ifLeft = { repositoryError ->
                        errorNotifier.repositoryError(
                            error = repositoryError,
                            shortMessage = "Failed to create new session"
                        )
                    },
                    ifRight = { newSession ->
                        cancelDialog()
                        selectSession(newSession.id)
                    }
                )
        }
    }

    /**
     * Renames a session (E2.S5).
     *
     * @param session The session summary to rename.
     * @param newName The new name for the session.
     */
    private fun renameSession(session: ChatSessionSummary, newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) {
            // Show inline validation error (UI concern) or update state with error
            println("Validation Error: Session name cannot be empty.")
            return
        }
        viewModelScope.launch(uiDispatcher) {
            sessionRepository.updateSessionName(session.id, UpdateSessionNameRequest(trimmedName)).fold(
                ifLeft = { repositoryError ->
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to rename session"
                    )
                },
                ifRight = {
                    cancelDialog()
                }
            )
        }
    }

    /**
     * Deletes a chat session (E2.S6).
     *
     * @param sessionId The ID of the session to delete.
     */
    private fun deleteSession(sessionId: Long) {
        viewModelScope.launch(uiDispatcher) {
            sessionRepository.deleteSession(sessionId).fold(
                ifLeft = { repositoryError ->
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to delete session"
                    )
                },
                ifRight = {
                    cancelDialog()
                }
            )
        }
    }

    /**
     * Assigns a session to a group or ungroups it (E6.S1, E6.S7).
     *
     * @param sessionId The ID of the session to move.
     * @param groupId The ID of the target group, or null to ungroup.
     */
    private fun assignSessionToGroup(sessionId: Long, groupId: Long?) {
        viewModelScope.launch(uiDispatcher) {
            sessionRepository.updateSessionGroup(sessionId, UpdateSessionGroupRequest(groupId)).fold(
                ifLeft = { repositoryError ->
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to assign session to group"
                    )
                },
                ifRight = {
                    cancelDialog()
                }
            )
        }
    }

    /**
     * Deletes a chat session group (E6.S6).
     *
     * @param groupId The ID of the group to delete.
     */
    private fun deleteGroup(groupId: Long) {
        viewModelScope.launch(uiDispatcher) {
            groupRepository.deleteGroup(groupId).fold(
                ifLeft = { repositoryError ->
                    errorNotifier.repositoryError(
                        error = repositoryError,
                        shortMessage = "Failed to delete group"
                        )
                    },
                    ifRight = {
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Updates the session name input in dialogs.
     * This is a private method used by the pre-bound dialog actions.
     *
     * @param newName The new session name input.
     */
    private fun updateDialogSessionName(newName: String) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is SessionListDialogState.NewSession -> dialogState.copy(sessionNameInput = newName)
                is SessionListDialogState.RenameSession -> dialogState.copy(newSessionNameInput = newName)
                else -> dialogState // No change for other states
            }
        }
    }
}