package eu.torvian.chatbot.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.flatMap
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.events.SnackbarInteractionEvent
import eu.torvian.chatbot.app.domain.events.apiRequestError
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.error_loading_sessions_groups
import eu.torvian.chatbot.app.service.api.GroupApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.utils.misc.ioDispatcher
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.getString

/**
 * Manages the UI state for the chat session list panel,
 * using KMP ViewModel, StateFlow, Arrow's Either, and UiState for loading states.
 *
 * This class is responsible for:
 * - Loading and holding the state (Idle/Loading/Success/Error) of the list of all chat sessions and groups.
 * - Structuring the session list visually by group ("Ungrouped" and defined groups).
 * - Managing the currently selected session ID.
 * - Handling user actions like creating, deleting, renaming sessions, and assigning sessions to groups.
 * - Handling user actions for managing groups (creating, renaming, deleting).
 * - Communicating with the backend via the SessionApi and GroupApi, handling their Either results.
 *
 * @constructor
 * @param sessionApi The API client for session-related operations.
 * @param groupApi The API client for group-related operations.
 * @param eventBus The event bus for emitting global events.
 * @param uiDispatcher The dispatcher to use for UI-related coroutines. Defaults to Main.
 * @param clock The clock to use for timestamping. Defaults to System clock.
 *
 * @property listState The state of the chat session list and groups.
 * @property selectedSessionId The ID of the currently selected session.
 * @property isCreatingNewGroup UI state indicating if the new group input field is visible.
 * @property newGroupNameInput Content of the new group input field.
 * @property editingGroup The group currently being edited/renamed. Null if none.
 * @property editingGroupNameInput Content of the editing group name input field.
 */
class SessionListViewModel(
    private val sessionApi: SessionApi,
    private val groupApi: GroupApi,
    private val eventBus: EventBus,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<SessionListViewModel>()
    }

    // --- Observable State for Compose UI (using StateFlow) ---

    private val _listState = MutableStateFlow<UiState<ApiError, SessionListData>>(UiState.Idle)

    /**
     * The state of the chat session list and groups.
     * When in Success state, provides the SessionListData object containing the lists
     * and the derived grouped structure.
     */
    val listState: StateFlow<UiState<ApiError, SessionListData>> = _listState.asStateFlow()


    private val _selectedSessionId = MutableStateFlow<Long?>(null)

    /**
     * The ID of the currently selected session.
     * This should be observed by the main chat UI to load the session details.
     */
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    private val _isCreatingNewGroup = MutableStateFlow(false)

    /**
     * UI state indicating if the new group input field is visible.
     */
    val isCreatingNewGroup: StateFlow<Boolean> = _isCreatingNewGroup.asStateFlow()

    private val _newGroupNameInput = MutableStateFlow("")

    /**
     * Content of the new group input field.
     */
    val newGroupNameInput: StateFlow<String> = _newGroupNameInput.asStateFlow()

    private val _editingGroup = MutableStateFlow<ChatGroup?>(null)

    /**
     * The group currently being edited/renamed. Null if none.
     */
    val editingGroup: StateFlow<ChatGroup?> = _editingGroup.asStateFlow()

    private val _editingGroupNameInput = MutableStateFlow("")

    /**
     * Content of the editing group name input field.
     */
    val editingGroupNameInput: StateFlow<String> = _editingGroupNameInput.asStateFlow()

    // Store the ID of the last emitted error if a retry is possible
    private val _lastFailedLoadEventId = MutableStateFlow<String?>(null)

    init {
        // ViewModel can listen to the EventBus for its own emitted event's responses
        viewModelScope.launch {
            eventBus.events.collect { event ->
                if (event is SnackbarInteractionEvent && event.originalAppEventId == _lastFailedLoadEventId.value) {
                    if (event.isActionPerformed) {
                        logger.info("Retrying loadSessionsAndGroups due to Snackbar action!")
                        _lastFailedLoadEventId.value = null // Clear ID before retrying
                        loadSessionsAndGroups() // Trigger retry
                    } else { // It was dismissed (by user or timeout)
                        logger.info("Snackbar dismissed, not retrying loadSessionsAndGroups.")
                        _lastFailedLoadEventId.value = null
                    }
                }
            }
        }
    }

    /**
     * Data class to hold the multiple pieces of data needed for the Session List UI when in Success state.
     * It holds the raw lists fetched from the backend and provides the derived grouped structure.
     */
    data class SessionListData(
        val allSessions: List<ChatSessionSummary> = emptyList(),
        val allGroups: List<ChatGroup> = emptyList()
    ) {
        /**
         * Returns the sessions organized by group, ready for display (E6.S2).
         * This is a derived property calculated based on [allSessions] and [allGroups].
         */
        val groupedSessions: Map<ChatGroup?, List<ChatSessionSummary>>
            get() {
                val ungrouped = allSessions.filter { it.groupId == null }.sortedByDescending { it.updatedAt }
                val grouped = allGroups.associateWith { group ->
                    allSessions.filter { it.groupId == group.id }.sortedByDescending { it.updatedAt }
                }
                return LinkedHashMap<ChatGroup?, List<ChatSessionSummary>>().apply {
                    put(null, ungrouped) // Null key for "Ungrouped" section
                    putAll(grouped)
                }
            }
    }

    // --- Public Action Functions (Called by UI Components) ---

    /**
     * Loads all session summaries and groups from the backend (E2.S3, E6.S4).
     * Uses parZip for concurrent loading.
     */
    fun loadSessionsAndGroups() {
        // Prevent loading if already loading
        if (_listState.value.isLoading) return

        viewModelScope.launch(uiDispatcher) {
            _listState.value = UiState.Loading // Set loading state

            // Use parZip to fetch sessions and groups concurrently
            parZip(
                ioDispatcher,
                { sessionApi.getAllSessions() },
                { groupApi.getAllGroups() }
            ) { sessionsEither, groupsEither ->
                // Handle the results - if either is an error, flatten to that error.
                // If both are success, combine them into SessionListData.
                sessionsEither.flatMap { sessions ->
                    groupsEither.map { groups ->
                        SessionListData(allSessions = sessions, allGroups = groups)
                    }
                }
            }.fold(
                ifLeft = { error ->
                    // Handle Error case (E2.S3, E6.S4 error)
                    _listState.value = UiState.Error(error)
                    // Emit to generic EventBus using the specific error type
                    val globalError = apiRequestError(
                        apiError = error,
                        shortMessage = getString(Res.string.error_loading_sessions_groups),
                        isRetryable = true
                    )
                    _lastFailedLoadEventId.value = globalError.eventId // Store its ID
                    eventBus.emitEvent(globalError)
                },
                ifRight = { data ->
                    // Handle Success case (E2.S3, E6.S4 success)
                    _listState.value = UiState.Success(data)
                    _lastFailedLoadEventId.value = null // Clear ID on success
                }
            )
        }
    }

    /**
     * Selects a chat session from the list, updating the [selectedSessionId].
     * (E2.S4)
     *
     * @param sessionId The ID of the session to select.
     */
    fun selectSession(sessionId: Long) {
        _selectedSessionId.value = sessionId
        // The UI component displaying ChatViewModel should react to this ID change
        // and call ChatViewModel.loadSession(sessionId).
    }

    /**
     * Creates a new chat session (E2.S1).
     *
     * @param initialName Optional initial name.
     */
    fun createNewSession(initialName: String? = null) {
        viewModelScope.launch(uiDispatcher) {
            // Optionally show a more granular loading state for just session creation
            val currentData = _listState.value.dataOrNull // Get current data if state is Success

            sessionApi.createSession(CreateSessionRequest(name = initialName))
                .fold(
                    ifLeft = { error ->
                        // Handle Error case (E2.S1 error)
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error creating new session"
                            )
                        )
                    },
                    ifRight = { newSession ->
                        // Handle Success (E2.S1)
                        val newSummary = ChatSessionSummary( // Create summary from full session object
                            id = newSession.id,
                            name = newSession.name,
                            createdAt = newSession.createdAt,
                            updatedAt = newSession.updatedAt,
                            groupId = newSession.groupId,
                            groupName = null // Grouping logic will derive this from updated groups list
                        )

                        // Update the list state by adding the new session summary
                        // We need the current data to update it, must be in Success state.
                        if (currentData != null) {
                            val updatedSessions = currentData.allSessions + newSummary
                            _listState.value = UiState.Success(currentData.copy(allSessions = updatedSessions))
                            selectSession(newSession.id) // Also updates _selectedSessionId
                        } else {
                            // This case shouldn't happen if we can create a session, but handle defensively
                            println("Warning: State was not Success when creating new session. Reloading list.")
                            loadSessionsAndGroups() // Reload everything to ensure consistency
                        }
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
    fun renameSession(session: ChatSessionSummary, newName: String) {
        val trimmedName = newName.trim()
        if (trimmedName.isBlank()) {
            // Show inline validation error (UI concern) or update state with error
            println("Validation Error: Session name cannot be empty.")
            return
        }
        viewModelScope.launch(uiDispatcher) {
            val currentData = _listState.value.dataOrNull ?: return@launch

            sessionApi.updateSessionName(session.id, UpdateSessionNameRequest(trimmedName))
                .fold(
                    ifLeft = { error ->
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error renaming session"
                            )
                        )
                    },
                    ifRight = {
                        // Update the name in the internal allSessions list within the state (E2.S5)
                        val updatedSessions = currentData.allSessions.map {
                            if (it.id == session.id) it.copy(
                                name = trimmedName,
                                updatedAt = clock.now()
                            ) else it
                        }
                        _listState.value = UiState.Success(currentData.copy(allSessions = updatedSessions))
                        // groupedSessions derived property will react
                    }
                )
        }
    }

    /**
     * Deletes a chat session (E2.S6).
     *
     * @param sessionId The ID of the session to delete.
     */
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(uiDispatcher) {
            val currentData = _listState.value.dataOrNull ?: return@launch

            sessionApi.deleteSession(sessionId)
                .fold(
                    ifLeft = { error ->
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error deleting session"
                            )
                        )
                    },
                    ifRight = {
                        // Remove from the internal list within the state (E2.S6)
                        val updatedSessions = currentData.allSessions.filter { it.id != sessionId }
                        _listState.value = UiState.Success(currentData.copy(allSessions = updatedSessions))

                        // If the deleted session was selected, deselect it
                        if (_selectedSessionId.value == sessionId) {
                            _selectedSessionId.value = null
                            // UI should react (e.g., ChatViewModel should transition to Idle/clear session)
                        }
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
    fun assignSessionToGroup(sessionId: Long, groupId: Long?) {
        viewModelScope.launch(uiDispatcher) {
            val currentData = _listState.value.dataOrNull ?: return@launch

            sessionApi.updateSessionGroup(sessionId, UpdateSessionGroupRequest(groupId))
                .fold(
                    ifLeft = { error ->
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error assigning session to group"
                            )
                        )
                    },
                    ifRight = {
                        // Update the session summary in the internal list within the state (E6.S1)
                        // Find the session, update its groupId and groupName, update the list
                        val updatedSessions = currentData.allSessions.map { session ->
                            if (session.id == sessionId) {
                                val groupName =
                                    currentData.allGroups.find { it.id == groupId }?.name // Find group name locally
                                session.copy(
                                    groupId = groupId,
                                    groupName = groupName,
                                    updatedAt = clock.now()
                                )
                            } else {
                                session
                            }
                        }
                        _listState.value = UiState.Success(currentData.copy(allSessions = updatedSessions))
                        // groupedSessions derived property will react
                    }
                )
        }
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
        _newGroupNameInput.value = "" // Clear previous input
        // Clear error state related to group creation if any
    }

    /**
     * Cancels the new group creation process.
     */
    fun cancelCreatingNewGroup() {
        _isCreatingNewGroup.value = false
        _newGroupNameInput.value = ""
        // Clear error state related to group creation if any
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
            val currentData = _listState.value.dataOrNull ?: return@launch

            groupApi.createGroup(CreateGroupRequest(name))
                .fold(
                    ifLeft = { error ->
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error creating new group"
                            )
                        )
                    },
                    ifRight = { newGroup ->
                        // Add the new group to the internal list within the state (E6.S3)
                        val updatedGroups = currentData.allGroups + newGroup
                        _listState.value = UiState.Success(currentData.copy(allGroups = updatedGroups))
                        // groupedSessions derived property will react
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
        // Clear error state related to group renaming if any
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
            val currentData = _listState.value.dataOrNull ?: return@launch

            groupApi.renameGroup(groupToRename.id, RenameGroupRequest(newName))
                .fold(
                    ifLeft = { error ->
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error renaming group"
                            )
                        )
                    },
                    ifRight = {
                        // Update the name in the internal _allGroups list within the state (E6.S5)
                        val updatedGroups = currentData.allGroups.map {
                            if (it.id == groupToRename.id) it.copy(name = newName) else it
                        }
                        // Also update the groupName in session summaries that reference this group within the state
                        val updatedSessions = currentData.allSessions.map { session ->
                            if (session.groupId == groupToRename.id) {
                                session.copy(groupName = newName)
                            } else {
                                session
                            }
                        }

                        _listState.value =
                            UiState.Success(currentData.copy(allGroups = updatedGroups, allSessions = updatedSessions))
                        // groupedSessions derived property will react
                        cancelRenamingGroup() // Hide input on success
                    }
                )
        }
    }

    /**
     * Deletes a chat session group (E6.S6).
     *
     * @param groupId The ID of the group to delete.
     */
    fun deleteGroup(groupId: Long) {
        viewModelScope.launch(uiDispatcher) {
            val currentData = _listState.value.dataOrNull ?: return@launch

            groupApi.deleteGroup(groupId)
                .fold(
                    ifLeft = { error ->
                        eventBus.emitEvent(
                            apiRequestError(
                                apiError = error,
                                shortMessage = "Error deleting group"
                            )
                        )
                    },
                    ifRight = {
                        // Remove the group from the internal list within the state (E6.S6)
                        val updatedGroups = currentData.allGroups.filter { it.id != groupId }

                        // The backend ungrouped sessions belonging to this group (E6.S6).
                        // We reflect this in our allSessions state locally within the state
                        val updatedSessions = currentData.allSessions.map {
                            if (it.groupId == groupId) {
                                it.copy(groupId = null, groupName = null)
                            } else {
                                it
                            }
                        }
                        _listState.value =
                            UiState.Success(currentData.copy(allGroups = updatedGroups, allSessions = updatedSessions))
                        // groupedSessions derived property will react
                    }
                )
        }
    }
}