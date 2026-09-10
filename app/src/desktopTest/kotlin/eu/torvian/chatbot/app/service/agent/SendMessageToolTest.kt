package eu.torvian.chatbot.app.service.agent

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.chat.SpawnedChatViewModelResolver
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.agent.SendMessageRequest
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

/**
 * Tests for [SendMessageTool] (the per-tool [OperatorTool] for `send_message`).
 *
 * Covers driving the target session's own [ChatViewModel] (load → input → send), per-mode result
 * shaping (wait = session-id-prefixed new assistant message; fire-and-forget = success notification
 * without joining or force-cancelling the background turn), error reporting for
 * decode/viewmodel-resolution/send-refusal/role-resolution failures, and the wait-mode newness
 * guard. The per-tool implementation is exercised through its [OperatorTool.execute] contract; the
 * router that dispatches to it (and to the `spawn_agent` tool) is covered by
 * [DefaultOperatorToolExecutorTest].
 */
class SendMessageToolTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val userId = 11L

    private val role = AgentRoleDto(
        id = 7L,
        name = "implementer",
        displayName = "Implementer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 5L,
        tools = setOf(1L),
        instructions = emptyList()
    )

    /**
     * Existing target session the executor drives; server already validated existence and same-user
     * ownership before the relay.
     */
    private val targetSession = ChatSession(
        id = 88L,
        name = "Caller session",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = role.id,
        currentLeafMessageId = null
    )

    /**
     * Encodes a valid `send_message` request targeting the existing [targetSession].
     *
     * @param toolCallId Correlation identifier carried by the request.
     * @param chatSessionId Target session identifier.
     * @param message Message text injected into the target session.
     * @param mode Execution mode carried by the request.
     * @return JSON payload accepted by the app-side executor.
     */
    private fun sendPayload(
        toolCallId: Long = 42L,
        chatSessionId: Long = targetSession.id,
        message: String = "Hello from the caller",
        mode: OperatorToolMode = OperatorToolMode.WAIT_FOR_RESPONSE
    ): String =
        json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(
                chatSessionId = chatSessionId,
                message = message,
                mode = mode,
                toolCallId = toolCallId
            )
        )

    private val settings = ChatModelSettings(
        id = role.modelSettingsId!!,
        modelId = role.modelId!!,
        name = "Chat profile",
        stream = false
    )

    private val model = LLMModel(
        id = role.modelId!!,
        name = "model",
        providerId = 1L,
        active = true
    )

    private fun assistantMessage(id: Long, content: String) = ChatMessage.AssistantMessage(
        id = id,
        sessionId = targetSession.id,
        content = content,
        createdAt = now,
        updatedAt = now,
        parentMessageId = null,
        modelId = role.modelId,
        settingsId = role.modelSettingsId
    )

    /**
     * Builds the executor with the given collaborators; mocks the auth state for the user unless
     * told otherwise.
     */
    private fun newExecutor(
        authRepository: AuthRepository = authenticatedAuthRepository(),
        resolver: SpawnedChatViewModelResolver = mockk(),
    ) = SendMessageTool(authRepository, resolver)

    private fun authenticatedAuthRepository(): AuthRepository {
        val authRepository = mockk<AuthRepository>()
        every { authRepository.authState } returns MutableStateFlow(
            AuthState.Authenticated(userId = userId, username = "tester", permissions = emptyList())
        )
        return authRepository
    }

    /**
     * Returns a real job that is already completed, so `join()` returns immediately in the executor.
     * A MockK-mocked [Job] cannot be used: `join()` would have no configured answer.
     */
    private fun completedJob(): Job = Job().apply { complete() }

    /**
     * Builds a mocked [SpawnedChatViewModelResolver] returning a [ChatViewModel] configured to run a
     * complete successful turn against [targetSession]: a resolved role and settings, load/send
     * jobs that complete immediately, and a branch whose assistant message appears only **after**
     * the send starts (modeling the real turn pipeline; the wait-mode newness guard depends on the
     * post-send message being new).
     *
     * @param summary Content of the assistant message appended when the send completes; `null`
     *            yields a blank message (exercise of the wait-mode blank check).
     * @param sendJob Job returned from `sendMessage()`; `null` makes the send be refused.
     * @param message The exact message the executor is expected to inject via `updateInput`.
     */
    private fun successfulViewModel(
        summary: String? = "TARGET RESPONSE",
        sendJob: Job? = completedJob(),
        message: String = "Hello from the caller",
    ): Pair<SpawnedChatViewModelResolver, ChatViewModel> {
        val viewModel = mockk<ChatViewModel>()
        val displayedMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
        every { viewModel.sessionDataState } returns MutableStateFlow(DataState.Success(targetSession))
        every { viewModel.currentAgentRole } returns MutableStateFlow(role)
        every { viewModel.currentModel } returns MutableStateFlow(model)
        every { viewModel.currentSettings } returns MutableStateFlow(settings)
        every { viewModel.displayedMessages } returns displayedMessages
        every { viewModel.loadSession(targetSession.id, userId) } returns completedJob()
        every { viewModel.updateInput(message) } just runs
        every { viewModel.sendMessage() } answers {
            // The turn completes by appending the new assistant message to the displayed branch.
            displayedMessages.value = listOf(assistantMessage(1L, summary ?: ""))
            sendJob
        }
        every { viewModel.forceCancelSend() } just runs

        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(targetSession.id) } returns viewModel
        return resolver to viewModel
    }

    /**
     * Verifies that a `send_message` wait-mode call injects the message into the target session and
     * returns its last assistant message prefixed with the target session id.
     */
    @Test
    fun `send_message wait mode returns the target's last assistant message prefixed with its id`() = runTest {
        val (resolver, viewModel) = successfulViewModel(summary = "TARGET RESPONSE")
        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(),
            clientEvents = { result = it }
        )

        // The wait output tags the target chat session id (keeps concurrent multi-session sends
        // distinguishable) and labels the assistant response.
        assertEquals(
            "**Target chat session id:** 88\n\n**Response:**\n\nTARGET RESPONSE",
            result?.output
        )
        assertEquals(false, result?.isError)
        // The injected turn is driven through the target session's own ViewModel: load → input →
        // send, and the new assistant message is read back from the VM.
        verify { viewModel.loadSession(targetSession.id, userId) }
        verify { viewModel.updateInput("Hello from the caller") }
        verify { viewModel.sendMessage() }
    }

    /**
     * Regression test against template-induced indentation: an assistant response whose continuation
     * lines are flush-left must not drag the label lines (and the response's first line) into the
     * template's indentation. The previous `.trimIndent()`-based template measured the common indent
     * over the fully interpolated string, where the flush-left continuation lines of a multi-line
     * response pinned that indent at zero and left everything indented.
     */
    @Test
    fun `send_message keeps a multi-line response flush-left instead of indenting the labels`() = runTest {
        val multiLineSummary = "I've retrieved the local time.\n\nHere is the report:\n## Target Time"
        val (resolver, _) = successfulViewModel(summary = multiLineSummary)
        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(),
            clientEvents = { result = it }
        )

        // Every result line is flush-left: neither the labels nor the response pick up template
        // indentation.
        assertEquals(
            "**Target chat session id:** 88\n\n**Response:**\n\n$multiLineSummary",
            result?.output
        )
        assertEquals(false, result?.isError)
    }

    /**
     * Verifies that a `send_message` fire-and-forget call returns a plain success notification
     * without awaiting the target turn and without force-cancelling it.
     */
    @Test
    fun `send_message fire and forget mode returns a success notification without awaiting the target turn`() = runTest {
        val pendingSend = CompletableDeferred<Unit>()
        val (resolver, viewModel) = successfulViewModel(sendJob = pendingSend)
        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(mode = OperatorToolMode.FIRE_AND_FORGET),
            clientEvents = { result = it }
        )

        // The success notification is tagged with the target chat session id and notes that the
        // turn continues in the background.
        assertEquals(
            "**Target chat session id:** 88\n\n" +
                "Message sent successfully; the target conversation continues in the background.",
            result?.output
        )
        assertEquals(false, result?.isError)
        // The target turn is still running after the tool returned.
        assertTrue(pendingSend.isActive)
        verify { viewModel.sendMessage() }
        verify(exactly = 0) { viewModel.forceCancelSend() }
    }

    @Test
    fun `send_message reports a decode failure as an error result`() = runTest {
        val executor = newExecutor()
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 1L,
            payload = "not-json",
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("decode"))
        assertNull(result?.output)
    }

    @Test
    fun `send_message reports a target viewmodel resolution failure as an error result`() = runTest {
        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(targetSession.id) } throws RuntimeException("no destination owner published")
        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("resolve"))
        assertNull(result?.output)
    }

    @Test
    fun `send_message reports a refused send as an error result`() = runTest {
        val (resolver, _) = successfulViewModel(sendJob = null)
        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("refused"))
        assertNull(result?.output)
    }

    @Test
    fun `send_message reports when the target session cannot resolve role, model or settings`() = runTest {
        val viewModel = mockk<ChatViewModel>()
        every { viewModel.sessionDataState } returns MutableStateFlow(DataState.Success(targetSession))
        every { viewModel.currentAgentRole } returns MutableStateFlow(null)
        every { viewModel.currentModel } returns MutableStateFlow(null)
        every { viewModel.currentSettings } returns MutableStateFlow(null)
        every { viewModel.displayedMessages } returns MutableStateFlow(emptyList())
        every { viewModel.loadSession(targetSession.id, userId) } returns completedJob()
        every { viewModel.forceCancelSend() } just runs

        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(targetSession.id) } returns viewModel

        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("could not resolve"))
        assertNull(result?.output)
    }

    /**
     * Verifies the wait-mode newness guard: when the injected turn produces no new assistant
     * message (the branch keeps a message that already predated the send), the tool reports an
     * error instead of returning the stale message.
     */
    @Test
    fun `send_message wait mode rejects a stale last assistant message that predates the send`() = runTest {
        val viewModel = mockk<ChatViewModel>()
        every { viewModel.sessionDataState } returns MutableStateFlow(DataState.Success(targetSession))
        every { viewModel.currentAgentRole } returns MutableStateFlow(role)
        every { viewModel.currentModel } returns MutableStateFlow(model)
        every { viewModel.currentSettings } returns MutableStateFlow(settings)
        // The branch already contains an assistant message BEFORE the send (a history-bearing
        // target); the injected turn must produce a NEW message to be reportable.
        val displayedMessages = MutableStateFlow<List<ChatMessage>>(listOf(assistantMessage(1L, "OLD RESPONSE")))
        every { viewModel.displayedMessages } returns displayedMessages
        every { viewModel.loadSession(targetSession.id, userId) } returns completedJob()
        every { viewModel.updateInput(any()) } just runs
        // The injected turn produces no new assistant message: the branch keeps the pre-existing one.
        every { viewModel.sendMessage() } returns completedJob()
        every { viewModel.forceCancelSend() } just runs

        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(targetSession.id) } returns viewModel

        val executor = newExecutor(resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = sendPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("without an assistant message"))
        assertNull(result?.output)
    }
}