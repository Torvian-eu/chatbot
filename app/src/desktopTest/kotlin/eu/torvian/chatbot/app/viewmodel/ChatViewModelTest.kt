package eu.torvian.chatbot.app.viewmodel

import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.app.service.api.SessionApi
import eu.torvian.chatbot.app.testutils.data.*
import eu.torvian.chatbot.app.testutils.misc.TestClock
import eu.torvian.chatbot.app.testutils.viewmodel.returnsDelayed
import eu.torvian.chatbot.app.testutils.viewmodel.startCollecting
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.ChatSession
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class ChatViewModelTest {
    // Test dispatcher for controlling coroutine execution
    private val testDispatcher = StandardTestDispatcher()

    // Test clock for controlling time
    private lateinit var clock: TestClock

    // Mock the dependencies
    private val sessionApi: SessionApi = mockk()
    private val chatApi: ChatApi = mockk()

    // The ViewModel instance to test (System Under Test)
    private lateinit var viewModel: ChatViewModel

    // Collected emissions from the StateFlows for assertion
    private val collectedSessionStates = mutableListOf<UiState<ApiError, ChatSession>>()

    @BeforeEach
    fun setUp() {
        // Initialize the test clock with a fixed initial time
        val initialTime = LocalDateTime(2023, 1, 1, 10, 0, 0).toInstant(TimeZone.UTC)
        clock = TestClock(initialTime)
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(sessionApi, chatApi)
    }

    /**
     * Helper function to initialize ViewModel and start collecting its key StateFlows
     */
    private fun TestScope.initViewModelAndCollect() {
        // Initialize the ViewModel, injecting mocked dependencies
        viewModel = ChatViewModel(sessionApi, chatApi, testDispatcher, clock)
        // Start collecting the StateFlows in the background using the test dispatcher's scope
        // The initial value is collected immediately by startCollecting.
        startCollecting(viewModel.sessionState, collectedSessionStates)
        // Run any pending coroutines to ensure initial values are collected
        advanceUntilIdle()
        runCurrent()
    }

    /**
     * Helper function to clear collected emissions before an action to focus on new emissions
     */
    private fun clearCollected() {
        collectedSessionStates.clear()
    }

    // --- Test Cases ---

    @Test
    fun initial_state_is_correct() = runTest(testDispatcher) {
        // --- Act ---
        // Initialize the ViewModel and start collecting its StateFlows
        initViewModelAndCollect()

        // --- Assert ---
        assertEquals(1, collectedSessionStates.size)
        assertTrue(collectedSessionStates.first().isIdle, "Initial sessionState should be Idle")
        assertTrue(
            viewModel.displayedMessages.value.isEmpty(),
            "Initial displayedMessages should be empty"
        )
        assertNull(viewModel.currentBranchLeafId.value, "Initial currentBranchLeafId should be null")
        assertEquals("", viewModel.inputContent.value, "Initial inputContent should be empty")
        assertNull(viewModel.replyTargetMessage.value, "Initial replyTargetMessage should be null")
        assertNull(viewModel.editingMessage.value, "Initial editingMessage should be null")
        assertEquals("", viewModel.editingContent.value, "Initial editingContent should be empty")

    }

    // --- Session Loading Tests ---

    @Test
    fun loadSession_success_updatesState() = runTest(testDispatcher) {
        // --- Arrange ---
        // Mock the API call to return a session with threaded messages
        val sessionId = 1L
        val session = mockSession2_threaded
        coEvery { sessionApi.getSessionDetails(sessionId) }.returnsDelayed(right(session))

        // --- Act ---
        // Initialize the ViewModel and start collecting its StateFlows
        initViewModelAndCollect()
        // Clear initial state emissions to focus on emissions triggered by loadSession
        clearCollected()
        // Load the session
        viewModel.loadSession(sessionId)

        // Advance time to ensure any pending work completes
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        // Check the StateFlows for the expected emissions
        assertEquals(2, collectedSessionStates.size, "sessionState should emit Loading and then Success")
        assertEquals(UiState.Loading, collectedSessionStates[0], "First emission of sessionState should be Loading")
        assertEquals(
            UiState.Success(session),
            collectedSessionStates[1],
            "Second emission of sessionState should be Success with the loaded session"
        )

        assertEquals(
            session.currentLeafMessageId,
            viewModel.currentBranchLeafId.value,
            "currentBranchLeafId should match session's leaf ID"
        )

        assertEquals(
            listOf(m1, m2, m3),
            viewModel.displayedMessages.value,
            "displayedMessages should show the correct branch"
        )

        // Verify the API call was made exactly once
        coVerify(exactly = 1) { sessionApi.getSessionDetails(sessionId) }

        // Check other synchronous flows were cleared at the start of loadSession
        assertNull(viewModel.replyTargetMessage.value, "replyTargetMessage should be null after calling loadSession")
        assertNull(viewModel.editingMessage.value, "editingMessage should be null after calling loadSession")
        assertEquals("", viewModel.editingContent.value, "editingContent should be empty after calling loadSession")
    }

    @Test
    fun loadSession_error_updatesState() = runTest(testDispatcher) {
        // --- Arrange ---
        // Mock the API call to return an error
        val sessionId = 1L
        val error = genericApiError(statusCode = 404, code = "not-found", message = "Session not found")
        coEvery { sessionApi.getSessionDetails(sessionId) }.returnsDelayed(left(error))

        // --- Act ---
        initViewModelAndCollect()
        clearCollected()
        viewModel.loadSession(sessionId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(2, collectedSessionStates.size, "sessionState should emit Loading and then Error")
        assertEquals(UiState.Loading, collectedSessionStates[0], "First emission of sessionState should be Loading")
        assertEquals(
            UiState.Error(error),
            collectedSessionStates[1],
            "Second emission of sessionState should be Error"
        )
        assertNull(viewModel.currentBranchLeafId.value, "currentBranchLeafId should be null after error")
        assertTrue(
            viewModel.displayedMessages.value.isEmpty(),
            "displayedMessages should be empty after error"
        )

        // Verify the API call was made exactly once
        coVerify(exactly = 1) { sessionApi.getSessionDetails(sessionId) }
    }

    // --- Message Input Tests ---

    @Test
    fun updateInput_updatesState() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        val newContent = "Hello, world!"

        // --- Act ---
        viewModel.updateInput(newContent)

        // --- Assert ---
        assertEquals(newContent, viewModel.inputContent.value, "inputContent should be updated")
    }

    @Test
    fun sendMessage_success_updatesStateAndClearsInput() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val newUserMessage =
            userMessage(id = 8, sessionId = session.id, content = "New message", parentMessageId = m3.id)
        val newAssistantMessage =
            assistantMessage(id = 9, sessionId = session.id, content = "Assistant response", parentMessageId = 8)
        val newMessages = listOf(newUserMessage, newAssistantMessage)

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { chatApi.processNewMessage(session.id, any()) }.returnsDelayed(right(newMessages))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.updateInput("New message")
        clearCollected()

        // --- Act ---
        viewModel.sendMessage()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals("", viewModel.inputContent.value, "inputContent should be cleared after sending")
        assertEquals(1, collectedSessionStates.size, "sessionState should emit Success with updated messages")

        val updatedSession = collectedSessionStates[0].dataOrNull
        assertEquals(session.messages.size + 2, updatedSession?.messages?.size, "Session should have 2 new messages")
        assertEquals(
            newAssistantMessage.id,
            updatedSession?.currentLeafMessageId,
            "Leaf ID should be updated to assistant message"
        )
        assertEquals(
            newAssistantMessage.id,
            viewModel.currentBranchLeafId.value,
            "currentBranchLeafId should be updated"
        )

        // Verify API call was made with correct parameters
        coVerify(exactly = 1) {
            chatApi.processNewMessage(
                session.id,
                match { it.content == "New message" && it.parentMessageId == m3.id }
            )
        }
    }

    @Test
    fun sendMessage_error_handlesError() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val error = genericApiError(statusCode = 500, code = "internal-error", message = "Failed to process message")

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { chatApi.processNewMessage(session.id, any()) }.returnsDelayed(left(error))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.updateInput("Test message")
        clearCollected()

        // --- Act ---
        viewModel.sendMessage()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals("", viewModel.inputContent.value, "inputContent should still be cleared")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values on error")

        // Verify API call was made
        coVerify(exactly = 1) { chatApi.processNewMessage(session.id, any()) }
    }

    @Test
    fun sendMessage_emptyContent_doesNothing() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.updateInput("   ") // Whitespace only
        clearCollected()

        // --- Act ---
        viewModel.sendMessage()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals("   ", viewModel.inputContent.value, "inputContent should not be cleared")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values")

        // Verify API call was not made
        coVerify(exactly = 0) { chatApi.processNewMessage(any(), any()) }
    }

    @Test
    fun sendMessage_noSessionLoaded_doesNothing() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        viewModel.updateInput("Test message")
        clearCollected()

        // --- Act ---
        viewModel.sendMessage()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals("Test message", viewModel.inputContent.value, "inputContent should not be cleared")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values")

        // Verify API call was not made
        coVerify(exactly = 0) { chatApi.processNewMessage(any(), any()) }
    }

    // --- Reply Functionality Tests ---

    @Test
    fun startReplyTo_setsReplyTarget() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        val targetMessage = m2

        // --- Act ---
        viewModel.startReplyTo(targetMessage)

        // --- Assert ---
        assertEquals(targetMessage, viewModel.replyTargetMessage.value, "replyTargetMessage should be set")
    }

    @Test
    fun cancelReply_clearsReplyTarget() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        viewModel.startReplyTo(m2)

        // --- Act ---
        viewModel.cancelReply()

        // --- Assert ---
        assertNull(viewModel.replyTargetMessage.value, "replyTargetMessage should be cleared")
    }

    @Test
    fun sendMessage_withReplyTarget_usesCorrectParent() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val replyTarget = m2
        val newUserMessage =
            userMessage(id = 8, sessionId = session.id, content = "Reply message", parentMessageId = replyTarget.id)
        val newAssistantMessage =
            assistantMessage(id = 9, sessionId = session.id, content = "Assistant response", parentMessageId = 8)
        val newMessages = listOf(newUserMessage, newAssistantMessage)

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { chatApi.processNewMessage(session.id, any()) }.returnsDelayed(right(newMessages))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.updateInput("Reply message")
        viewModel.startReplyTo(replyTarget)
        clearCollected()

        // --- Act ---
        viewModel.sendMessage()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertNull(viewModel.replyTargetMessage.value, "replyTargetMessage should be cleared after sending")

        // Verify API call was made with correct parent ID
        coVerify(exactly = 1) {
            chatApi.processNewMessage(
                session.id,
                match { it.content == "Reply message" && it.parentMessageId == replyTarget.id }
            )
        }
    }

    // --- Message Editing Tests ---

    @Test
    fun startEditing_setsEditingState() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        val messageToEdit = m1

        // --- Act ---
        viewModel.startEditing(messageToEdit)

        // --- Assert ---
        assertEquals(messageToEdit, viewModel.editingMessage.value, "editingMessage should be set")
        assertEquals(
            messageToEdit.content,
            viewModel.editingContent.value,
            "editingContent should be set to message content"
        )
    }

    @Test
    fun updateEditingContent_updatesContent() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        viewModel.startEditing(m1)
        val newContent = "Updated content"

        // --- Act ---
        viewModel.updateEditingContent(newContent)

        // --- Assert ---
        assertEquals(newContent, viewModel.editingContent.value, "editingContent should be updated")
    }

    @Test
    fun saveEditing_success_updatesMessage() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val messageToEdit = m1
        val updatedMessage = messageToEdit.copy(content = "Updated content")

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { chatApi.updateMessageContent(messageToEdit.id, any()) }.returnsDelayed(right(updatedMessage))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.startEditing(messageToEdit)
        viewModel.updateEditingContent("Updated content")
        clearCollected()

        // --- Act ---
        viewModel.saveEditing()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertNull(viewModel.editingMessage.value, "editingMessage should be cleared after saving")
        assertEquals("", viewModel.editingContent.value, "editingContent should be cleared after saving")

        assertEquals(1, collectedSessionStates.size, "sessionState should emit Success with updated message")
        val updatedSession = collectedSessionStates[0].dataOrNull
        val updatedMessageInSession = updatedSession?.messages?.find { it.id == messageToEdit.id }
        assertEquals(
            "Updated content",
            updatedMessageInSession?.content,
            "Message content should be updated in session"
        )

        // Verify API call was made with correct parameters
        coVerify(exactly = 1) {
            chatApi.updateMessageContent(
                messageToEdit.id,
                match { it.content == "Updated content" }
            )
        }
    }

    @Test
    fun saveEditing_error_handlesError() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val messageToEdit = m1
        val error = genericApiError(statusCode = 500, code = "internal-error", message = "Failed to update message")

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { chatApi.updateMessageContent(messageToEdit.id, any()) }.returnsDelayed(left(error))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.startEditing(messageToEdit)
        viewModel.updateEditingContent("Updated content")
        clearCollected()

        // --- Act ---
        viewModel.saveEditing()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(messageToEdit, viewModel.editingMessage.value, "editingMessage should remain set on error")
        assertEquals("Updated content", viewModel.editingContent.value, "editingContent should remain set on error")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values on error")

        // Verify API call was made
        coVerify(exactly = 1) { chatApi.updateMessageContent(messageToEdit.id, any()) }
    }

    @Test
    fun saveEditing_emptyContent_doesNothing() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        viewModel.startEditing(m1)
        viewModel.updateEditingContent("   ") // Whitespace only
        clearCollected()

        // --- Act ---
        viewModel.saveEditing()
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(m1, viewModel.editingMessage.value, "editingMessage should remain set")
        assertEquals("   ", viewModel.editingContent.value, "editingContent should remain set")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values")

        // Verify API call was not made
        coVerify(exactly = 0) { chatApi.updateMessageContent(any(), any()) }
    }

    @Test
    fun cancelEditing_clearsEditingState() = runTest(testDispatcher) {
        // --- Arrange ---
        initViewModelAndCollect()
        viewModel.startEditing(m1)
        viewModel.updateEditingContent("Some content")

        // --- Act ---
        viewModel.cancelEditing()

        // --- Assert ---
        assertNull(viewModel.editingMessage.value, "editingMessage should be cleared")
        assertEquals("", viewModel.editingContent.value, "editingContent should be cleared")
    }

    // --- Branch Navigation Tests ---

    @Test
    fun switchBranchToMessage_success_updatesBranch() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val targetMessageId = m5.id // Switch to different branch

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { sessionApi.updateSessionLeafMessage(session.id, any()) }.returnsDelayed(right(Unit))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        clearCollected()

        // --- Act ---
        viewModel.switchBranchToMessage(targetMessageId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(targetMessageId, viewModel.currentBranchLeafId.value, "currentBranchLeafId should be updated")
        assertEquals(
            listOf(m1, m4, m5),
            viewModel.displayedMessages.value,
            "displayedMessages should show the new branch"
        )

        assertEquals(1, collectedSessionStates.size, "sessionState should emit Success with updated leaf")
        val updatedSession = collectedSessionStates[0].dataOrNull
        assertEquals(targetMessageId, updatedSession?.currentLeafMessageId, "Session leaf ID should be updated")

        // Verify API call was made with correct parameters
        coVerify(exactly = 1) {
            sessionApi.updateSessionLeafMessage(
                session.id,
                match { it.leafMessageId == targetMessageId }
            )
        }
    }

    @Test
    fun switchBranchToMessage_error_handlesError() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val targetMessageId = m5.id
        val error = genericApiError(statusCode = 500, code = "internal-error", message = "Failed to update leaf")

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { sessionApi.updateSessionLeafMessage(session.id, any()) }.returnsDelayed(left(error))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        clearCollected()

        // --- Act ---
        viewModel.switchBranchToMessage(targetMessageId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(
            targetMessageId,
            viewModel.currentBranchLeafId.value,
            "currentBranchLeafId should be optimistically updated"
        )
        assertEquals(
            listOf(m1, m4, m5),
            viewModel.displayedMessages.value,
            "displayedMessages should show the new branch optimistically"
        )

        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values on error")

        // Verify API call was made
        coVerify(exactly = 1) { sessionApi.updateSessionLeafMessage(session.id, any()) }
    }

    @Test
    fun switchBranchToMessage_invalidMessageId_doesNothing() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val invalidMessageId = 999L

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        val originalLeafId = viewModel.currentBranchLeafId.value
        clearCollected()

        // --- Act ---
        viewModel.switchBranchToMessage(invalidMessageId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(originalLeafId, viewModel.currentBranchLeafId.value, "currentBranchLeafId should not change")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values")

        // Verify API call was not made
        coVerify(exactly = 0) { sessionApi.updateSessionLeafMessage(any(), any()) }
    }

    @Test
    fun switchBranchToMessage_sameMessage_doesNothing() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val currentLeafId = session.currentLeafMessageId!!

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        clearCollected()

        // --- Act ---
        viewModel.switchBranchToMessage(currentLeafId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(currentLeafId, viewModel.currentBranchLeafId.value, "currentBranchLeafId should remain the same")
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values")

        // Verify API call was not made
        coVerify(exactly = 0) { sessionApi.updateSessionLeafMessage(any(), any()) }
    }

    // --- Model Selection Tests ---

    @Test
    fun selectModel_success_updatesSession() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val newModelId = 42L

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { sessionApi.updateSessionModel(session.id, any()) }.returnsDelayed(right(Unit))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        clearCollected()

        // --- Act ---
        viewModel.selectModel(newModelId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(1, collectedSessionStates.size, "sessionState should emit Success with updated model")
        val updatedSession = collectedSessionStates[0].dataOrNull
        assertEquals(newModelId, updatedSession?.currentModelId, "Session model ID should be updated")

        // Verify API call was made with correct parameters
        coVerify(exactly = 1) {
            sessionApi.updateSessionModel(
                session.id,
                match { it.modelId == newModelId }
            )
        }
    }

    @Test
    fun selectModel_error_handlesError() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded
        val newModelId = 42L
        val error = genericApiError(statusCode = 500, code = "internal-error", message = "Failed to update model")

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { sessionApi.updateSessionModel(session.id, any()) }.returnsDelayed(left(error))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        clearCollected()

        // --- Act ---
        viewModel.selectModel(newModelId)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(0, collectedSessionStates.size, "sessionState should not emit new values on error")

        // Verify API call was made
        coVerify(exactly = 1) { sessionApi.updateSessionModel(session.id, any()) }
    }

    // --- Thread Building Tests ---

    @Test
    fun displayedMessages_updatesWhenBranchChanges() = runTest(testDispatcher) {
        // --- Arrange ---
        val session = mockSession2_threaded

        coEvery { sessionApi.getSessionDetails(session.id) }.returnsDelayed(right(session))
        coEvery { sessionApi.updateSessionLeafMessage(session.id, any()) }.returnsDelayed(right(Unit))

        initViewModelAndCollect()
        viewModel.loadSession(session.id)
        advanceUntilIdle()
        runCurrent()

        // Initially should show branch m1->m2->m3
        assertEquals(
            listOf(m1, m2, m3),
            viewModel.displayedMessages.value,
            "Initial displayedMessages should show correct branch"
        )

        // --- Act ---
        // Switch to branch m1->m4->m5
        viewModel.switchBranchToMessage(m5.id)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(
            listOf(m1, m4, m5),
            viewModel.displayedMessages.value,
            "displayedMessages should update to show new branch"
        )
    }

    @Test
    fun displayedMessages_handlesEmptySession() = runTest(testDispatcher) {
        // --- Arrange ---
        val emptySession = mockSession1_empty

        coEvery { sessionApi.getSessionDetails(emptySession.id) }.returnsDelayed(right(emptySession))

        initViewModelAndCollect()

        // --- Act ---
        viewModel.loadSession(emptySession.id)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertTrue(
            viewModel.displayedMessages.value.isEmpty(),
            "displayedMessages should be empty for empty session"
        )
        assertNull(viewModel.currentBranchLeafId.value, "currentBranchLeafId should be null for empty session")
    }

    @Test
    fun displayedMessages_handlesSingleMessageSession() = runTest(testDispatcher) {
        // --- Arrange ---
        val singleMessageSession = mockSession3_singleMessage

        coEvery { sessionApi.getSessionDetails(singleMessageSession.id) }.returnsDelayed(right(singleMessageSession))

        initViewModelAndCollect()

        // --- Act ---
        viewModel.loadSession(singleMessageSession.id)
        advanceUntilIdle()
        runCurrent()

        // --- Assert ---
        assertEquals(
            listOf(m1.copy(childrenMessageIds = emptyList())),
            viewModel.displayedMessages.value,
            "displayedMessages should show single message"
        )
        assertEquals(m1.id, viewModel.currentBranchLeafId.value, "currentBranchLeafId should be the single message ID")
    }
}
