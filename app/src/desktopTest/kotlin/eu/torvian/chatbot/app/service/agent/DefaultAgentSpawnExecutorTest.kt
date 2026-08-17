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
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
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
 * Tests for [DefaultAgentSpawnExecutor].
 *
 * Covers tool-type dispatch, session creation + role attach, driving the spawned session's own
 * [ChatViewModel] (load → input → send, awaiting each step), result aggregation from the last
 * assistant message, error reporting for decode/session/role-resolution/send-refusal failures, and
 * cancellation of the spawned send when the primary turn closes mid-spawn.
 */
class DefaultAgentSpawnExecutorTest {

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
     * @return JSON payload accepted by the app-side executor.
     */
    private fun spawnPayload(toolCallId: Long = 42L, prompt: String = "Do the thing"): String =
        json.encodeToString(
            AgentSpawnRequest.serializer(),
            AgentSpawnRequest(
                agentRoleToSpawn = role,
                subject = "Implementation task",
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
    ) = DefaultAgentSpawnExecutor(sessionRepository, authRepository, resolver)

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
     * immediately, and [summary] as the aggregated last assistant message.
     */
    private fun successfulViewModel(
        summary: String? = "FINAL SUMMARY",
        sendJob: Job? = completedJob(),
    ): Pair<SpawnedChatViewModelResolver, ChatViewModel> {
        val viewModel = mockk<ChatViewModel>()
        every { viewModel.sessionDataState } returns MutableStateFlow(DataState.Success(session))
        every { viewModel.currentAgentRole } returns MutableStateFlow(role)
        every { viewModel.currentModel } returns MutableStateFlow(model)
        every { viewModel.currentSettings } returns MutableStateFlow(settings)
        every { viewModel.displayedMessages } returns MutableStateFlow(
            listOf(assistantMessage(1L, summary ?: ""))
        )
        every { viewModel.loadSession(session.id, userId) } returns completedJob()
        every { viewModel.updateInput("Do the thing") } just runs
        every { viewModel.sendMessage() } returns sendJob
        every { viewModel.lastAssistantMessageContent() } returns summary
        every { viewModel.forceCancelSend() } just runs

        val resolver = mockk<SpawnedChatViewModelResolver>()
        coEvery { resolver.forSession(session.id) } returns viewModel
        return resolver to viewModel
    }

    @Test
    fun `execute reports an unknown tool type as an error result`() = runTest {
        val executor = newExecutor()
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 1L,
            toolName = "future_tool",
            payload = "{}",
            clientEvents = { result = it }
        )

        assertEquals(1L, result?.toolCallId)
        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("future_tool"))
    }

    @Test
    fun `execute reports a decode failure as an error result`() = runTest {
        val executor = newExecutor()
        var result: ChatClientEvent.ToolExecutionResult? = null

        executor.execute(
            toolCallId = 1L,
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
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
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = payload,
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("no user prompt"))
    }

    /**
     * Verifies that the supplied subject becomes the prefixed session name before the turn runs.
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
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals("FINAL SUMMARY", result?.output)
        assertNotEquals(result?.isError, true)
        coVerify { sessionRepository.createSession("Spawned: Implementation task") }
        coVerify { sessionRepository.updateSessionAgentRole(session.id, role.id) }
        // The spawned conversation is driven through the session's own ViewModel with user-facing
        // methods: load → input → send, and the turn's final state is read back from the VM.
        verify { viewModel.loadSession(session.id, userId) }
        verify { viewModel.updateInput("Do the thing") }
        verify { viewModel.sendMessage() }
        verify { viewModel.lastAssistantMessageContent() }
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
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("cannot create"))
        assertNull(result?.output)
    }

    @Test
    fun `execute reports a role attach failure as an error result`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns
                RepositoryError.OtherError("cannot attach").left()

        val executor = newExecutor(sessionRepository = sessionRepository)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("cannot attach"))
    }

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
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("not authenticated"))
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
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("could not resolve"))
    }

    @Test
    fun `execute reports a refused send as an error result`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val (resolver, _) = successfulViewModel(sendJob = null)
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("refused"))
    }

    @Test
    fun `execute reports an empty summary as an error result`() = runTest {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.createSession(any()) } returns session.right()
        coEvery { sessionRepository.updateSessionAgentRole(session.id, role.id) } returns Unit.right()

        val (resolver, _) = successfulViewModel(summary = null)
        val executor = newExecutor(sessionRepository = sessionRepository, resolver = resolver)
        var result: ChatClientEvent.ToolExecutionResult? = null
        executor.execute(
            toolCallId = 42L,
            toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
            payload = spawnPayload(),
            clientEvents = { result = it }
        )

        assertEquals(true, result?.isError)
        assertEquals(true, result?.errorMessage?.contains("without an assistant summary"))
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
                toolName = OperatorToolCatalog.SPAWN_AGENT_NAME,
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
}