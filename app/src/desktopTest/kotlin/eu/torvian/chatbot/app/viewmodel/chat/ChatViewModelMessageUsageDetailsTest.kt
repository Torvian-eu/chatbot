package eu.torvian.chatbot.app.viewmodel.chat

import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.viewmodel.SearchNavigationState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatAreaDialogState
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.chat.usecase.CopyToClipboardUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.DeleteMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.EditMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.FileReferenceUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.InsertMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.LoadAgentRolesUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.LoadProjectsUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.LoadSessionUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.ReplyUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SelectAgentRoleUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SelectProjectUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SendMessageUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.SwitchBranchUseCase
import eu.torvian.chatbot.app.viewmodel.chat.usecase.UpdateInputUseCase
import eu.torvian.chatbot.common.models.core.UsageStats
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests how [ChatViewModel.showMessageUsageDetails] resolves the provenance names of a message and which state it
 * publishes.
 *
 * The names are resolved when the dialog opens, from the lookup maps the view model already holds, so an id the
 * maps cannot resolve must degrade to `null` (the dialog renders its own fallback) instead of failing to open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelMessageUsageDetailsTest {

    private lateinit var state: ChatState
    private lateinit var viewModel: ChatViewModel

    private val model = LLMModel(
        id = 5L,
        name = "gpt-4o",
        providerId = 1L,
        active = true,
        displayName = "Resolved Model"
    )

    private val settings = ChatModelSettings(
        id = 6L,
        name = "Resolved profile",
        modelId = 5L,
        temperature = 0.2f,
        maxTokens = 1000,
        stream = false,
        stopSequences = null,
        customParams = null
    )

    @BeforeTest
    fun setup() {
        state = mockk(relaxed = true)
        every { state.modelsById } returns MutableStateFlow(mapOf(model.id to model))
        every { state.settingsById } returns MutableStateFlow(mapOf(settings.id to settings))

        val scope = CoroutineScope(UnconfinedTestDispatcher())
        viewModel = ChatViewModel(
            state = state,
            loadSessionUC = mockk<LoadSessionUseCase>(relaxed = true),
            sendMessageUC = mockk<SendMessageUseCase>(relaxed = true),
            replyUC = mockk<ReplyUseCase>(relaxed = true),
            editMessageUC = mockk<EditMessageUseCase>(relaxed = true),
            deleteMessageUC = mockk<DeleteMessageUseCase>(relaxed = true),
            insertMessageUC = mockk<InsertMessageUseCase>(relaxed = true),
            switchBranchUC = mockk<SwitchBranchUseCase>(relaxed = true),
            selectAgentRoleUC = mockk<SelectAgentRoleUseCase>(relaxed = true),
            loadAgentRolesUC = mockk<LoadAgentRolesUseCase>(relaxed = true),
            selectProjectUC = mockk<SelectProjectUseCase>(relaxed = true),
            loadProjectsUC = mockk<LoadProjectsUseCase>(relaxed = true),
            updateInputUC = mockk<UpdateInputUseCase>(relaxed = true),
            copyToClipboardUC = mockk<CopyToClipboardUseCase>(relaxed = true),
            fileReferenceUC = mockk<FileReferenceUseCase>(relaxed = true),
            navigationState = mockk<SearchNavigationState>(relaxed = true),
            normalScope = scope,
            backgroundScope = scope
        )
    }

    @Test
    fun `showMessageUsageDetails publishes the message with the names of its model and settings profile`() = runTest {
        val usage = UsageStats(inputTokens = 10, outputTokens = 5, totalTokens = 15, reasoningTokens = 2)
        val message = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Answer",
            modelId = model.id,
            settingsId = settings.id
        ).copy(usageStats = usage)
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit

        viewModel.showMessageUsageDetails(message)

        val dialog = assertIs<ChatAreaDialogState.MessageUsageDetails>(dialogSlot.captured)
        assertEquals(message, dialog.message)
        assertEquals(usage, dialog.message.usageStats)
        assertEquals("Resolved Model", dialog.modelDisplayName)
        assertEquals("Resolved profile", dialog.settingsDisplayName)
    }

    @Test
    fun `showMessageUsageDetails falls back to no names when the ids cannot be resolved`() = runTest {
        val message = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Answer",
            modelId = 999L,
            settingsId = 998L
        )
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit

        viewModel.showMessageUsageDetails(message)

        val dialog = assertIs<ChatAreaDialogState.MessageUsageDetails>(dialogSlot.captured)
        assertNull(dialog.modelDisplayName, "An unresolvable model falls back to the dialog's own placeholder")
        assertNull(dialog.settingsDisplayName)
    }

    @Test
    fun `showMessageUsageDetails falls back to no names when the message carries no ids`() = runTest {
        val message = assistantMessage(id = 2L, sessionId = 1L, content = "Answer")
        val dialogSlot = slot<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogSlot)) } returns Unit

        viewModel.showMessageUsageDetails(message)

        val dialog = assertIs<ChatAreaDialogState.MessageUsageDetails>(dialogSlot.captured)
        assertNull(dialog.modelDisplayName)
        assertNull(dialog.settingsDisplayName)
    }

    @Test
    fun `dismissing the usage details dialog clears the dialog state`() = runTest {
        val message = assistantMessage(
            id = 2L,
            sessionId = 1L,
            content = "Answer",
            modelId = model.id,
            settingsId = settings.id
        )
        val dialogs = mutableListOf<ChatAreaDialogState>()
        every { state.setDialogState(capture(dialogs)) } returns Unit

        viewModel.showMessageUsageDetails(message)
        assertIs<ChatAreaDialogState.MessageUsageDetails>(dialogs.last()).onDismiss()

        assertIs<ChatAreaDialogState.None>(dialogs.last())
        verify(exactly = 1) { state.setDialogState(any<ChatAreaDialogState.None>()) }
    }
}
