package eu.torvian.chatbot.app.viewmodel.chat

import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.testutils.data.userMessage
import eu.torvian.chatbot.app.viewmodel.SearchNavigationState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.state.TurnExecutionState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.*
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.MessageInsertPosition
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Tests for the defense-in-depth turn guards in [ChatViewModel].
 *
 * Conversation-mutating and turn-starting actions must be refused while an assistant turn is
 * active (RUNNING, PAUSING, or STOPPING), even if a caller bypasses the disabled UI controls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTurnGuardTest {

    private lateinit var state: ChatState
    private lateinit var sendMessageUC: SendMessageUseCase
    private lateinit var deleteMessageUC: DeleteMessageUseCase
    private lateinit var insertMessageUC: InsertMessageUseCase
    private lateinit var turnState: MutableStateFlow<TurnExecutionState>
    private lateinit var normalScope: CoroutineScope
    private lateinit var backgroundScope: CoroutineScope
    private lateinit var viewModel: ChatViewModel

    private val user = userMessage(id = 1L, sessionId = 1L, content = "Hello")
    private val assistant = assistantMessage(
        id = 2L,
        sessionId = 1L,
        content = "Response",
        parentMessageId = 1L
    )

    @BeforeTest
    fun setup() {
        state = mockk(relaxed = true)
        turnState = MutableStateFlow(TurnExecutionState.IDLE)
        every { state.turnExecutionState } returns turnState

        sendMessageUC = mockk(relaxed = true)
        deleteMessageUC = mockk(relaxed = true)
        insertMessageUC = mockk(relaxed = true)

        normalScope = CoroutineScope(UnconfinedTestDispatcher())
        backgroundScope = CoroutineScope(UnconfinedTestDispatcher())

        viewModel = ChatViewModel(
            state = state,
            loadSessionUC = mockk(relaxed = true),
            sendMessageUC = sendMessageUC,
            replyUC = mockk(relaxed = true),
            editMessageUC = mockk(relaxed = true),
            deleteMessageUC = deleteMessageUC,
            insertMessageUC = insertMessageUC,
            switchBranchUC = mockk(relaxed = true),
            selectAgentRoleUC = mockk(relaxed = true),
            loadAgentRolesUC = mockk(relaxed = true),
            updateInputUC = mockk(relaxed = true),
            copyToClipboardUC = mockk(relaxed = true),
            fileReferenceUC = mockk(relaxed = true),
            navigationState = mockk<SearchNavigationState>(relaxed = true),
            normalScope = normalScope,
            backgroundScope = backgroundScope
        )
    }

    @AfterTest
    fun tearDown() {
        clearMocks(state, sendMessageUC, deleteMessageUC, insertMessageUC)
    }

    // --- sendMessage ---

    @Test
    fun `sendMessage is allowed when idle`() = runTest {
        turnState.value = TurnExecutionState.IDLE

        viewModel.sendMessage()

        coVerify(exactly = 1) { sendMessageUC.execute(null) }
    }

    @Test
    fun `sendMessage is blocked while running`() = runTest {
        turnState.value = TurnExecutionState.RUNNING

        viewModel.sendMessage()

        coVerify(exactly = 0) { sendMessageUC.execute(any()) }
    }

    @Test
    fun `sendMessage with continueFromMessage is blocked while running`() = runTest {
        turnState.value = TurnExecutionState.RUNNING

        viewModel.sendMessage(continueFromMessage = assistant)

        coVerify(exactly = 0) { sendMessageUC.execute(any()) }
    }

    // --- regenerateMessage ---

    @Test
    fun `regenerateMessage is blocked while running`() = runTest {
        turnState.value = TurnExecutionState.RUNNING
        every { state.displayedMessages } returns MutableStateFlow(listOf(user, assistant))

        viewModel.regenerateMessage(assistant)

        coVerify(exactly = 0) { sendMessageUC.execute(any()) }
    }

    // --- requestDeleteMessage ---

    @Test
    fun `requestDeleteMessage is allowed when idle`() = runTest {
        turnState.value = TurnExecutionState.IDLE

        viewModel.requestDeleteMessage(assistant)

        verify(exactly = 1) {
            state.setDialogState(any<ChatAreaDialogState.DeleteMessage>())
        }
    }

    @Test
    fun `requestDeleteMessage is blocked while running`() = runTest {
        turnState.value = TurnExecutionState.RUNNING

        viewModel.requestDeleteMessage(assistant)

        verify(exactly = 0) { state.setDialogState(any()) }
    }

    @Test
    fun `requestDeleteMessageRecursively is blocked while running`() = runTest {
        turnState.value = TurnExecutionState.RUNNING

        viewModel.requestDeleteMessageRecursively(assistant)

        verify(exactly = 0) { state.setDialogState(any()) }
    }

    @Test
    fun `confirming delete after turn became active does not delete`() = runTest {
        // Arrange - open the delete dialog while idle
        turnState.value = TurnExecutionState.IDLE
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        viewModel.requestDeleteMessage(assistant)

        val deleteDialog = dialogSlot.captured as ChatAreaDialogState.DeleteMessage

        // Act - the turn becomes active while the dialog is open, then the user confirms
        turnState.value = TurnExecutionState.RUNNING
        deleteDialog.onDeleteConfirm()

        // Assert - the delete use case was never invoked
        coVerify(exactly = 0) { deleteMessageUC.execute(any(), any()) }
    }

    // --- onRequestInsertMessage ---

    @Test
    fun `onRequestInsertMessage is allowed when idle`() = runTest {
        turnState.value = TurnExecutionState.IDLE

        viewModel.onRequestInsertMessage(assistant)

        verify(exactly = 1) {
            state.setDialogState(any<ChatAreaDialogState.InsertMessage>())
        }
    }

    @Test
    fun `onRequestInsertMessage is blocked while running`() = runTest {
        turnState.value = TurnExecutionState.RUNNING

        viewModel.onRequestInsertMessage(assistant)

        verify(exactly = 0) { state.setDialogState(any()) }
    }

    @Test
    fun `confirming insert after turn became active does not insert`() = runTest {
        // Arrange - open the insert dialog while idle
        turnState.value = TurnExecutionState.IDLE
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit
        viewModel.onRequestInsertMessage(assistant)

        val insertDialog = dialogSlot.captured as ChatAreaDialogState.InsertMessage

        // Act - the turn becomes active while the dialog is open, then the user confirms
        turnState.value = TurnExecutionState.RUNNING
        insertDialog.onConfirm(MessageInsertPosition.BELOW, ChatMessage.Role.USER, "New message")

        // Assert - the insert use case was never invoked
        verify(exactly = 0) { insertMessageUC.execute(any(), any(), any(), any(), any()) }
    }
}
