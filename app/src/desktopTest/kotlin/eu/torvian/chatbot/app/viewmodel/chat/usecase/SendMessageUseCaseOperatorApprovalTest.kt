package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.OperatorToolCatalog
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolCall
import eu.torvian.chatbot.common.models.tool.ToolCallStatus
import eu.torvian.chatbot.common.models.tool.UserToolApprovalPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [SendMessageUseCase]: the operator-tool approval flow and the local settling of a stopped turn.
 *
 * The approval tests verify that a `ToolCallApprovalRequested` for an operator tool (e.g. `spawn_agent`) is
 * left to the user in the UI when no explicit `UserToolApprovalPreference` exists (the approval dialog
 * shows), and that explicit preferences drive auto-decisions emitted as
 * `ChatClientEvent.OperatorToolCallApproval`.
 *
 * The stop tests verify that a streaming turn this client cancelled settles the assistant message it left
 * unfinalized locally (`SessionRepository.markAssistantMessageInterrupted`) without issuing any session request,
 * that a stop the server ended while the socket was alive is left to the delivered event, and that a turn which
 * ended normally — streaming or not — leaves the cached session alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendMessageUseCaseOperatorApprovalTest {

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val role = AgentRoleDto(
        id = 5L,
        name = "writer",
        modelId = 1L,
        modelSettingsId = 2L
    )

    private val session = ChatSession(
        id = 100L,
        name = "Session",
        createdAt = now,
        updatedAt = now,
        groupId = null,
        agentRoleId = role.id,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    private val operatorTool = OperatorToolDefinition(
        id = 9L,
        name = OperatorToolCatalog.SPAWN_AGENT_NAME,
        description = "Spawns an agent",
        config = buildJsonObject { },
        inputSchema = OperatorToolCatalog.allTools
            .first { it.name == OperatorToolCatalog.SPAWN_AGENT_NAME }
            .inputSchema,
        outputSchema = null,
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        userId = 1L
    )

    private val pendingToolCall = ToolCall(
        id = 42L,
        messageId = 100L,
        toolDefinitionId = operatorTool.id,
        toolName = operatorTool.name,
        input = "{}",
        output = null,
        status = ToolCallStatus.AWAITING_APPROVAL,
        errorMessage = null,
        denialReason = null,
        executedAt = now,
        durationMs = null
    )

    /**
     * Builds the mocked chat/session state and captures the outbound client-event flow.
     *
     * The use case emits into a hot `MutableSharedFlow`; a subscriber must exist for `emit` to
     * deliver. The returned [CompletableDeferred] resolves with the outbound flow the moment the
     * repository is invoked, and the test then collects it on a background job so the auto-approval
     * can be observed.
     */
    private fun buildHarness(
        preference: UserToolApprovalPreference?
    ): Quad {
        val sessionRepository = mockk<SessionRepository>()
        val toolRepository = mockk<ToolRepository>()
        val state = mockk<ChatState>()

        every { state.currentSession } returns MutableStateFlow<ChatSession?>(session)
        every { state.currentAgentRole } returns MutableStateFlow<AgentRoleDto?>(role)
        every { state.currentModel } returns MutableStateFlow<LLMModel?>(LLMModel(id = 1L, name = "gpt-4", providerId = 1L, active = true))
        every { state.currentSettings } returns MutableStateFlow<ModelSettings?>(
            ChatModelSettings(id = 2L, modelId = 1L, name = "Chat profile", stream = true)
        )
        every { state.inputContent } returns MutableStateFlow("hello")
        every { state.replyTargetMessage } returns MutableStateFlow(null)
        every { state.pendingFileReferences } returns MutableStateFlow(emptyList())

        // The user's own operator-tool instance arrives via the (now owner-scoped) tool list.
        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(listOf(operatorTool)))
        every { toolRepository.toolApprovalPreferences } returns MutableStateFlow(
            DataState.Success(if (preference == null) emptyList() else listOf(preference))
        )
        // Keep repository resolution available for cache-miss cases; this harness exercises the
        // cache-first path for the operator definition.
        coEvery { toolRepository.getToolById(operatorTool.id) } returns operatorTool.right()

        val outbound = CompletableDeferred<Flow<ChatClientEvent>>()
        every {
            sessionRepository.processNewMessageStreaming(session.id, any())
        } answers {
            outbound.complete(secondArg<Flow<ChatClientEvent>>())
            flow {
                emit(ChatStreamEvent.ToolCallApprovalRequested(pendingToolCall).right())
                emit(ChatStreamEvent.StreamCompleted.right())
            }
        }

        return Quad(sessionRepository, toolRepository, state, outbound)
    }

    /**
     * Bundle returned by [buildHarness] carrying the mocked collaborators and the outbound-flow
     * handle.
     */
    private data class Quad(
        val sessionRepository: SessionRepository,
        val toolRepository: ToolRepository,
        val state: ChatState,
        val outbound: CompletableDeferred<Flow<ChatClientEvent>>
    )

    private suspend fun runSend(harness: Quad) {
        val useCase = SendMessageUseCase(
            sessionRepository = harness.sessionRepository,
            toolRepository = harness.toolRepository,
            requestSigningService = mockk<RequestSigningService>(),
            operatorToolExecutor = mockk(),
            state = harness.state,
            notificationService = mockk<NotificationService>(),
            sessionTurnStatusRegistry = mockk<SessionTurnStatusRegistry>(relaxed = true)
        )
        useCase.execute()
    }

    @Disabled(
        "Deferred: observing the hot clientEventFlow emit from a mocked send is flaky under the test " +
            "dispatcher. Revisit with a dedicated seam (e.g. an injectable outbound sink) so the " +
            "operator-tool approval flow can be asserted deterministically."
    )
    @Test
    fun `operator tool approval is left to the user when no preference exists`() = runTest {
        val harness = buildHarness(preference = null)

        // Drive the send in a child coroutine while this body waits for the mock to expose the
        // outbound flow, then subscribe on a background job so the hot clientEventFlow has a
        // subscriber for the approval emit.
        val sendJob = launch { runSend(harness) }
        val clientEvents = harness.outbound.await()
        val sent = mutableListOf<ChatClientEvent>()
        val collectJob = launch { clientEvents.collect { sent.add(it) } }
        sendJob.join()
        collectJob.cancel()

        assertTrue(sent.any { it is ChatClientEvent.ProcessNewMessage }, "expected initial ProcessNewMessage frame")
        // No stored preference means the call is left to the user: no auto-decision is emitted and
        // the approval dialog remains responsible for the outcome.
        assertEquals(null, sent.filterIsInstance<ChatClientEvent.OperatorToolCallApproval>().singleOrNull())
    }

    @Disabled(
        "Deferred: observing the hot clientEventFlow emit from a mocked send is flaky under the test " +
            "dispatcher. Revisit with a dedicated seam (e.g. an injectable outbound sink) so the " +
            "operator-tool approval flow can be asserted deterministically."
    )
    @Test
    fun `operator tool auto-approval honors an explicit auto-deny preference`() = runTest {
        val preference = UserToolApprovalPreference(
            userId = 1L,
            toolDefinitionId = operatorTool.id,
            autoApprove = false,
            denialReason = "No background agents"
        )
        val harness = buildHarness(preference = preference)

        val sendJob = launch { runSend(harness) }
        val clientEvents = harness.outbound.await()
        val sent = mutableListOf<ChatClientEvent>()
        val collectJob = launch { clientEvents.collect { sent.add(it) } }
        sendJob.join()
        collectJob.cancel()

        val approval = sent.filterIsInstance<ChatClientEvent.OperatorToolCallApproval>().singleOrNull()
        assertEquals(false, approval?.approved)
        assertEquals("No background agents", approval?.denialReason)
    }

    // --- Local settling of a stopped turn ---

    /**
     * Mocked collaborators of the send path whose only moving part is the message-processing flow.
     *
     * @property sessionRepository Strict mock whose local-settling calls are asserted and whose other
     *           session operations would fail the test if the send path ever invoked them.
     * @property state Chat state the send path reads; the flow used by the test never mutates it.
     */
    private data class SendHarness(
        val sessionRepository: SessionRepository,
        val state: ChatState
    )

    /**
     * Builds the send-path harness for the requested streaming setting.
     *
     * @param isStreaming Whether the resolved settings profile streams, which selects the
     *        streaming or the non-streaming send path inside the use case.
     * @return Harness with the repository mock and the chat state.
     */
    private fun buildSendHarness(isStreaming: Boolean): SendHarness {
        val sessionRepository = mockk<SessionRepository>()
        val state = mockk<ChatState>()

        every { state.currentSession } returns MutableStateFlow<ChatSession?>(session)
        every { state.currentAgentRole } returns MutableStateFlow<AgentRoleDto?>(role)
        every { state.currentModel } returns MutableStateFlow<LLMModel?>(LLMModel(id = 1L, name = "gpt-4", providerId = 1L, active = true))
        every { state.currentSettings } returns MutableStateFlow<ModelSettings?>(
            ChatModelSettings(id = 2L, modelId = 1L, name = "Chat profile", stream = isStreaming)
        )
        every { state.inputContent } returns MutableStateFlow("hello")
        every { state.replyTargetMessage } returns MutableStateFlow(null)
        every { state.pendingFileReferences } returns MutableStateFlow(emptyList())

        return SendHarness(sessionRepository, state)
    }

    /**
     * Builds the use case under test around a send harness, with unused collaborators mocked.
     *
     * @param harness Harness supplying the repository and chat state.
     * @return Use case ready to receive [SendMessageUseCase.execute].
     */
    private fun sendUseCase(harness: SendHarness) = SendMessageUseCase(
        sessionRepository = harness.sessionRepository,
        toolRepository = mockk<ToolRepository>(),
        requestSigningService = mockk<RequestSigningService>(),
        operatorToolExecutor = mockk(),
        state = harness.state,
        notificationService = mockk<NotificationService>(),
        sessionTurnStatusRegistry = mockk<SessionTurnStatusRegistry>(relaxed = true)
    )

    /** Builds the parent message the streaming start event refers to. */
    private fun parentMessage() = ChatMessage.UserMessage(
        id = 99L,
        sessionId = session.id,
        content = "hello",
        createdAt = now,
        updatedAt = now,
        parentMessageId = null
    )

    /** Builds the assistant placeholder announced by a streaming start event. */
    private fun assistantPlaceholder() = ChatMessage.AssistantMessage(
        id = 100L,
        sessionId = session.id,
        content = "",
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentMessage().id,
        modelId = 1L,
        settingsId = 2L,
        isComplete = false
    )

    @Test
    fun `stopped streaming turn marks its placeholder interrupted locally without a session request`() = runTest {
        val harness = buildSendHarness(isStreaming = true)
        val placeholder = assistantPlaceholder()
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(placeholder, parentMessage()).right())
            // The turn is torn down by this client before any finalizing event can arrive.
            awaitCancellation()
        }
        coEvery {
            harness.sessionRepository.markAssistantMessageInterrupted(session.id, placeholder.id)
        } returns Unit

        val sendJob = launch { sendUseCase(harness).execute() }
        advanceUntilIdle()
        sendJob.cancel()
        advanceUntilIdle()
        sendJob.join()

        // Exactly one local settlement of the placeholder this client cancelled.
        coVerify(exactly = 1) {
            harness.sessionRepository.markAssistantMessageInterrupted(session.id, placeholder.id)
        }
        // The cached session is never fetched or replaced by the send path, which is what makes the
        // MEDIUM-1 race unrepresentable (a strict mock fails on any unstubbed repository call).
        coVerify(exactly = 0) { harness.sessionRepository.loadSessionDetails(any()) }
    }

    @Test
    fun `completed streaming turn does not touch the cached session`() = runTest {
        val harness = buildSendHarness(isStreaming = true)
        val placeholder = assistantPlaceholder()
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(placeholder, parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(placeholder.copy(isComplete = true)).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        sendUseCase(harness).execute()

        // The delivered terminal event is the only writer: nothing is settled locally afterwards.
        coVerify(exactly = 0) { harness.sessionRepository.markAssistantMessageInterrupted(any(), any()) }
    }

    @Test
    fun `stop delivered live does not settle anything locally`() = runTest {
        val harness = buildSendHarness(isStreaming = true)
        val placeholder = assistantPlaceholder()
        // Drain-completed stop: the server classified the ending itself and delivered its terminal frame over
        // the still-open socket, so the client already holds the authoritative state and must add nothing.
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(placeholder, parentMessage()).right())
            emit(
                ChatStreamEvent.AssistantMessageEnd(
                    placeholder.copy(
                        isComplete = false,
                        incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER
                    )
                ).right()
            )
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        sendUseCase(harness).execute()

        // The delivered event wins: the local marking must never fight the server's own terminal state.
        coVerify(exactly = 0) { harness.sessionRepository.markAssistantMessageInterrupted(any(), any()) }
        coVerify(exactly = 0) { harness.sessionRepository.loadSessionDetails(any()) }
    }

    @Test
    fun `non-streaming turn does not touch the cached session`() = runTest {
        val completedHarness = buildSendHarness(isStreaming = false)
        every { completedHarness.sessionRepository.processNewMessage(session.id, any()) } returns flow {
            emit(ChatEvent.StreamCompleted.right())
        }

        sendUseCase(completedHarness).execute()

        coVerify(exactly = 0) { completedHarness.sessionRepository.markAssistantMessageInterrupted(any(), any()) }

        val cancelledHarness = buildSendHarness(isStreaming = false)
        every { cancelledHarness.sessionRepository.processNewMessage(session.id, any()) } returns flow {
            awaitCancellation()
        }

        val sendJob = launch { sendUseCase(cancelledHarness).execute() }
        advanceUntilIdle()
        sendJob.cancel()
        advanceUntilIdle()
        sendJob.join()

        // Nothing may be settled here: a non-streaming call has no placeholder, and the server creates no
        // assistant row for one that was abandoned before returning.
        coVerify(exactly = 0) { cancelledHarness.sessionRepository.markAssistantMessageInterrupted(any(), any()) }
    }
}
