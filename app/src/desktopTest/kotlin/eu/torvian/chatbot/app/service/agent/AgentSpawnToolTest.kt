package eu.torvian.chatbot.app.service.agent

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AuthRepository
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.app.viewmodel.chat.SpawnedChatViewModelResolver
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.agent.AgentSpawnMessage
import eu.torvian.chatbot.common.models.agent.AgentSpawnRequest
import eu.torvian.chatbot.common.models.agent.OperatorToolMode
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

/**
 * Tests for [AgentSpawnTool] (the per-tool [OperatorTool] for `spawn_agent`).
 *
 * Covers session creation + role attach, driving the spawned session's own [ChatViewModel]
 * (load → input → send), per-mode result shaping (wait = session-id-prefixed summary;
 * fire-and-forget = immediate session id without joining or force-cancelling the background turn),
 * error reporting for decode/session/role-resolution/send-refusal failures, and the mode-aware
 * cleanup on mid-spawn executor cancellation. The per-tool implementation is exercised through its
 * [OperatorTool.execute] contract; the router that dispatches to it (and to the `send_message`
 * tool) is covered by [DefaultOperatorToolExecutorTest].
 */
class AgentSpawnToolTest {

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
     * Session returned by the repository after applying the spawned-session name.
     */
    private val session = ChatSession(
        id = 99L,
        name = "Spawned: Implementation task",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = role.id,
        currentLeafMessageId = null
    )

    /**
     * Encodes a valid spawn request with a subject used to verify session naming.
     *
     * @param toolCallId Correlation identifier carried by the request.
     * @param prompt User message sent to the spawned agent.
     * @param mode Execution mode carried by the request (defaults to wait-for-response so the
     *            default-mode tests stay pinned to summary-return behavior).
     * @return JSON payload accepted by the app-side executor.
     */
    private fun spawnPayload(
        toolCallId: Long = 42L,
        prompt: String = "Do the thing",
        mode: OperatorToolMode = OperatorToolMode.WAIT_FOR_RESPONSE
    ): String =
        json.encodeToString(
            AgentSpawnRequest.serializer(),
            AgentSpawnRequest(
                agentRoleToSpawn = role,
                subject = "Implementation task",
                mode = mode,
                conversation = listOf(AgentSpawnMessage.User(prompt)),
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
        sessionId = session.id,
        content = content,
        createdAt = now,
        updatedAt = now,
        parentMessageId = null,
        modelId = role.modelId,
        settingsId = role.modelSettingsId
    )

    /**
     * Builds an executor with the given collaborators; mocks the auth state for the user unless told
     * otherwise, so tests only stub what they exercise.
     */
    private fun newExecutor(
        sessionRepository: SessionRepository = mockk(),
        authRepository: AuthRepository = authenticatedAuthRepository(),
        resolver: SpawnedChatViewModelResolver = mockk(),
    ) = AgentSpawnTool(sessionRepository, authRepository, resolver)

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
     * complete successful turn: a resolved role and settings, load/send jobs that complete
     * immediately, and a branch whose assistant message appears only **after** the send starts
     * (modeling the real turn pipeline; the wait-mode newness guard depends on the post-send
     * message being new).
     *
     * @param summary Content of the assistant message appended when the send completes; `null`
     *            yields a blank message (exercise of the wait-mode blank check).
     * @param sendJob Job returned from `sendMessage()`; `null` makes the send be refused.
     * @param message The exact message the executor is expected to inject via `updateInput`.
     */
    private fun successfulViewModel(
        summary: String? = "FINAL SUMMARY",
        sendJob: Job? = completedJob(),
        message: String = "Do the thing",
    ): Pair<SpawnedChatViewModelResolver, ChatViewModel> {
        val viewModel = mockk<ChatViewModel>()
        val displayedMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
        every { viewModel.sessionDataState } returns MutableStateFlow(DataState.Success(session))
        every { viewModel.currentAgentRole } returns MutableStateFlow(role)
        every { viewModel.currentModel } returns MutableStateFlow(model)
        every { viewModel.currentSettings } returns MutableStateFlow(settings)
        every { viewModel.displayedMessages } returns displayedMessages
        every { viewModel.loadSession(session.id, userId) } returns completedJob()
        every { viewModel.updateInput(message) } just runs
        every { viewModel.sendMessage() } answers {
            // The turn completes by appending the new assistant message to the displayed branch.
            displayedMessages.value = listOf(assistantMessage(1L, summary ?: ""))
            sendJob
        }
        every { viewModel.forceCancelSend() } just runs

        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(session.id) } returns viewModel
        return resolver to viewModel
    }

    @Test
    fun `execute reports a spawn decode failure as an error result`() = runTest {
        val executor = newExecutor()
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 1L,
            payload = "not-json",
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("decode"))
    }

    /**
     * Verifies that a request without a user message is rejected before session creation.
     */
    @Test
    fun `execute reports a missing prompt as an error result`() = runTest {
        val executor = newExecutor()
        var result: ChatClientEvent.ToolExecutionResult? = null
        val payload = json.encodeToString(
            AgentSpawnRequest.serializer(),
            AgentSpawnRequest(
                agentRoleToSpawn = role,
                subject = "Implementation task",
                conversation = emptyList(),
                toolCallId = 42L
            )
        )

        executor.execute(
            toolCallId = 42L,
            payload = payload,
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("no user prompt"))
    }

    /**
     * Verifies that the supplied subject becomes the prefixed session name before the turn runs and
     * that the wait-mode result is the summary prefixed with the spawned session id.
     */
    @Test
    fun `execute drives the spawned session viewmodel and aggregates its summary`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession("Spawned: Implementation task") } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val (resolver, viewModel) = successfulViewModel(summary = "FINAL SUMMARY")
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        // Wait success: spawn-id label, then the labeled assistant response.
        assertEquals(
            "**Spawned chat session id:** 99\n\n**Response:**\n\nFINAL SUMMARY",
            result?.output
        )
        assertEquals(false, result?.isError)
        coVerify { sessionRepository.createSession("Spawned: Implementation task") }
        coVerify { sessionRepository.updateSessionAgentRole(session.id, role.id) }
        // The spawned conversation is driven through the session's own ViewModel with user-facing
        // methods: load → input → send, and the turn's final state is read back from the VM.
        verify { viewModel.loadSession(session.id, userId) }
        verify { viewModel.updateInput("Do the thing") }
        verify { viewModel.sendMessage() }
    }

    /**
     * Verifies fire-and-forget spawn mode: the session is created with the role attached, the first
     * turn is still started through the ViewModel (load → input → send), but the tool returns the
     * spawned session id **without awaiting the send job** and without force-cancelling the
     * background turn.
     */
    @Test
    fun `execute in fire and forget spawn mode returns the session id without awaiting the send`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession("Spawned: Implementation task") } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        // A send job that never completes proves the executor returned without joining it.
        val pendingSend = CompletableDeferred<Unit>()
        val (resolver, viewModel) = successfulViewModel(sendJob = pendingSend)
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(mode = OperatorToolMode.FIRE_AND_FORGET),
            clientEvents = { result = it }
        )

        // Instant success carries the spawn-id label plus a status line (no summary yet: the turn
        // continues in the background).
        assertEquals(
            "**Spawned chat session id:** 99\n\n" +
                "The spawned conversation started; its first turn continues in the background.",
            result?.output
        )
        assertEquals(false, result?.isError)
        // The tool returned while the spawned turn's send job is still pending.
        assertTrue(pendingSend.isActive)
        // The first turn still started with the prompt through the session's own ViewModel.
        verify { viewModel.loadSession(session.id, userId) }
        verify { viewModel.updateInput("Do the thing") }
        verify { viewModel.sendMessage() }
        // Fire-and-forget never force-cancels the background turn it just started.
        verify(exactly = 0) { viewModel.forceCancelSend() }
    }

    @Test
    fun `execute reports a session creation failure as an error result`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns
                RepositoryError.OtherError("cannot create").left()

        val executor = newExecutor(sessionRepository = sessionRepository)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("cannot create"))
        // Pre-session failure: no valid session id exists, so the error carries no id.
        assertNull(result?.output)
    }

    /**
     * Verifies that a role-attach failure (after the session was created) reports a readable error
     * and — in wait mode — still carries the spawned session id so the caller can reach it.
     */
    @Test
    fun `execute reports a role attach failure as an error result with the session id`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns
                RepositoryError.OtherError("cannot attach").left()

        val executor = newExecutor(sessionRepository = sessionRepository)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("cannot attach"))
        // A valid session id exists at this point: wait-mode failures carry it.
        assertEquals("**Spawned chat session id:** 99", result?.output)
    }

    /**
     * Verifies that an unauthenticated spawn (after the session was created) reports a readable
     * error that — in wait mode — still carries the spawned session id.
     */
    @Test
    fun `execute reports when the user is not authenticated`() = runTest {
        val authRepository = mockk<AuthRepository>()
        every { authRepository.authState } returns MutableStateFlow(AuthState.Unauthenticated)

        // Session creation and role attach run before the auth check in the executor; stub them so
        // the test reaches the authentication guard.
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val executor = newExecutor(sessionRepository = sessionRepository, authRepository = authRepository)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("not authenticated"))
        assertEquals("**Spawned chat session id:** 99", result?.output)
    }

    @Test
    fun `execute reports when the spawned session cannot resolve role, model or settings`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val viewModel = mockk<ChatViewModel>()
        every { viewModel.sessionDataState } returns MutableStateFlow(DataState.Success(session))
        every { viewModel.currentAgentRole } returns MutableStateFlow(null)
        every { viewModel.currentModel } returns MutableStateFlow(null)
        every { viewModel.currentSettings } returns MutableStateFlow(null)
        every { viewModel.displayedMessages } returns MutableStateFlow(emptyList())
        every { viewModel.loadSession(session.id, userId) } returns completedJob()
        every { viewModel.forceCancelSend() } just runs

        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(session.id) } returns viewModel

        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("could not resolve"))
        assertEquals("**Spawned chat session id:** 99", result?.output)
    }

    @Test
    fun `execute reports a refused send as an error result with the session id`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val (resolver, _) = successfulViewModel(sendJob = null)
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("refused"))
        assertEquals("**Spawned chat session id:** 99", result?.output)
    }

    /**
     * Verifies that a wait-mode spawn whose first turn produced no assistant summary reports an
     * error that still carries the spawned session id.
     */
    @Test
    fun `execute reports an empty summary as an error result with the session id`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val (resolver, _) = successfulViewModel(summary = null)
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("without an assistant summary"))
        assertEquals("**Spawned chat session id:** 99", result?.output)
    }

    @Test
    fun `execute cancels the spawned send when the primary turn is cancelled mid-spawn`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        // A send job that never completes keeps the spawned turn in flight until the primary scope
        // (and therefore the executor coroutine) is cancelled.
        val pendingSend = CompletableDeferred<Unit>()
        val (resolver, viewModel) = successfulViewModel(sendJob = pendingSend)
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)

        val executorJob = launch {
            executor.execute(
                toolCallId = 42L,
                    payload = spawnPayload(),
                clientEvents = {}
            )
        }
        yield()
        testScheduler.runCurrent()
        executorJob.cancelAndJoin()

        // The spawned send runs in the spawned ViewModel's own scope, so it must be force-cancelled
        // when the primary turn is cancelled instead of being orphaned.
        verify { viewModel.forceCancelSend() }
    }

    /**
     * Verifies that even when the executor coroutine is cancelled mid-flight, fire-and-forget mode
     * never force-cancels the background turn (the mode-aware `finally` deliberately skips
     * `forceCancelSend` unconditionally for immediate mode).
     */
    @Test
    fun `execute in fire and forget mode never force-cancels the spawned turn on executor cancellation`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val (resolver, viewModel) = successfulViewModel()
        // A load that never completes keeps the executor coroutine suspended until it is cancelled,
        // giving the test a deterministic mid-execution cancellation point.
        val neverCompletingLoad = CompletableDeferred<Unit>()
        every { viewModel.loadSession(session.id, userId) } returns neverCompletingLoad
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)

        val executorJob = launch {
            executor.execute(
                toolCallId = 42L,
                    payload = spawnPayload(mode = OperatorToolMode.FIRE_AND_FORGET),
                clientEvents = {}
            )
        }
        yield()
        testScheduler.runCurrent()
        executorJob.cancelAndJoin()

        // Immediate mode must not force-cancel, even on executor-coroutine cancellation (and the
        // send never started here because the load did not complete — the point is the cleanup is
        // skipped unconditionally).
        verify(exactly = 0) { viewModel.forceCancelSend() }
        verify(exactly = 0) { viewModel.sendMessage() }
    }
}