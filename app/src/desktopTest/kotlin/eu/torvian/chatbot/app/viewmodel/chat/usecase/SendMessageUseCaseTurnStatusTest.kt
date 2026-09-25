package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import eu.torvian.chatbot.app.viewmodel.sessionstatus.TurnOutcome
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/**
 * Tests for the session-status reporting of [SendMessageUseCase]: turn start, the awaiting-input
 * sub-state around tool-call approvals, and the terminal outcome classification.
 *
 * The registry is mocked so each test can assert exactly which lifecycle signals the use case
 * emits for its turn, including the interrupt cases that must never earn a completion badge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendMessageUseCaseTurnStatusTest {

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
     * Mocked collaborators of the send path plus the registry whose signals are asserted.
     *
     * @property sessionRepository Strict mock whose message-processing flow is supplied per test.
     * @property toolRepository Tool cache and approval preferences used by the approval path.
     * @property state Chat state the send path reads.
     * @property registry Registry capturing the reported turn lifecycle signals.
     */
    private data class Harness(
        val sessionRepository: SessionRepository,
        val toolRepository: ToolRepository,
        val state: ChatState,
        val registry: SessionTurnStatusRegistry
    )

    /**
     * Builds the send-path harness with the requested streaming setting.
     *
     * @param isStreaming Whether the resolved settings profile streams, which selects the streaming
     *        or the non-streaming send path inside the use case.
     * @param preference Stored approval preference, or `null` when approvals stay manual.
     * @return Harness with strict repository mocks and a relaxed registry mock.
     */
    private fun buildHarness(
        isStreaming: Boolean,
        preference: UserToolApprovalPreference? = null
    ): Harness {
        val sessionRepository = mockk<SessionRepository>()
        val toolRepository = mockk<ToolRepository>()
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

        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(listOf(operatorTool)))
        every { toolRepository.toolApprovalPreferences } returns MutableStateFlow(
            DataState.Success(if (preference == null) emptyList() else listOf(preference))
        )

        return Harness(
            sessionRepository = sessionRepository,
            toolRepository = toolRepository,
            state = state,
            registry = mockk<SessionTurnStatusRegistry>(relaxed = true)
        )
    }

    /**
     * Builds the use case under test around a harness.
     *
     * @param harness Harness supplying the collaborators.
     * @return Use case ready to receive [SendMessageUseCase.execute].
     */
    private fun useCase(harness: Harness) = SendMessageUseCase(
        sessionRepository = harness.sessionRepository,
        toolRepository = harness.toolRepository,
        requestSigningService = mockk<RequestSigningService>(),
        operatorToolExecutor = mockk(),
        state = harness.state,
        notificationService = mockk<NotificationService>(relaxed = true),
        sessionTurnStatusRegistry = harness.registry
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

    /**
     * Builds an assistant message for terminal-event fixtures.
     *
     * @param isComplete Whether the message reached its completed terminal state.
     * @param incompleteCause Terminal cause of an incomplete message, if any.
     * @return Assistant message on the harness session.
     */
    private fun assistantMessage(
        isComplete: Boolean,
        incompleteCause: AssistantMessageIncompleteCause? = null
    ) = ChatMessage.AssistantMessage(
        id = 100L,
        sessionId = session.id,
        content = "answer",
        createdAt = now,
        updatedAt = now,
        parentMessageId = parentMessage().id,
        modelId = 1L,
        settingsId = 2L,
        isComplete = isComplete,
        incompleteCause = incompleteCause,
        errorCode = if (incompleteCause == AssistantMessageIncompleteCause.FAILED) {
            AssistantMessageErrorCode.AUTHENTICATION_FAILED
        } else {
            null
        }
    )

    // --- Outcome classification ---

    @Test
    fun `streaming turn reports start and a success outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(assistantMessage(isComplete = true)).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        useCase(harness).execute()

        verify { harness.registry.onTurnStarted(session.id) }
        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.SUCCESS) }
    }

    @Test
    fun `failed terminal message reports a failure outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(
                ChatStreamEvent.AssistantMessageEnd(
                    assistantMessage(
                        isComplete = false,
                        incompleteCause = AssistantMessageIncompleteCause.FAILED
                    )
                ).right()
            )
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        useCase(harness).execute()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `interrupted terminal message reports no outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(
                ChatStreamEvent.AssistantMessageEnd(
                    assistantMessage(
                        isComplete = false,
                        incompleteCause = AssistantMessageIncompleteCause.INTERRUPTED_BY_USER
                    )
                ).right()
            )
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        useCase(harness).execute()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, null) }
    }

    @Test
    fun `turn ending in error without a completed message reports a failure outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ErrorOccurred(ApiError(500, "internal", "boom")).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        useCase(harness).execute()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `completed message after an error still reports a success outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ErrorOccurred(ApiError(500, "internal", "recoverable")).right())
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(assistantMessage(isComplete = true)).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        useCase(harness).execute()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.SUCCESS) }
    }

    @Test
    fun `cancelling mid tool execution reports no outcome despite an intermediate complete message`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            // An intermediate tool-loop iteration reaches a complete message while the tool runs.
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(assistantMessage(isComplete = true)).right())
            awaitCancellation()
        }

        val sendJob = launch { useCase(harness).execute() }
        advanceUntilIdle()
        sendJob.cancel()
        advanceUntilIdle()
        sendJob.join()

        // Cancellation outranks message completeness, so the interruption never reads as success.
        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, null) }
        verify(exactly = 0) { harness.registry.onTurnFinished(session.id, TurnOutcome.SUCCESS) }
        verify(exactly = 0) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `repository error without a completed message reports a failure outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(RepositoryError.OtherError("websocket closed").left())
        }

        useCase(harness).execute()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `repository error after a completed message still reports a success outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(assistantMessage(isComplete = true)).right())
            emit(RepositoryError.OtherError("websocket closed").left())
        }

        useCase(harness).execute()

        // A completed terminal message outranks the error, which only marks the abnormal ending.
        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.SUCCESS) }
        verify(exactly = 0) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `repository flow failure without a completed message reports no outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            throw IllegalStateException("socket torn down")
        }

        assertFailsWith<IllegalStateException> { useCase(harness).execute() }

        // Only failures delivered through the flow end the turn in error; a thrown failure escapes
        // to the caller unclassified and leaves the turn without an outcome.
        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, null) }
    }

    @Test
    fun `non-streaming repository error without a completed message reports a failure outcome`() = runTest {
        val harness = buildHarness(isStreaming = false)
        every { harness.sessionRepository.processNewMessage(session.id, any()) } returns flow {
            emit(RepositoryError.OtherError("websocket closed").left())
        }

        useCase(harness).execute()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `cancelling a turn after a repository error reports no outcome`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(RepositoryError.OtherError("websocket closed").left())
            awaitCancellation()
        }

        val sendJob = launch { useCase(harness).execute() }
        advanceUntilIdle()
        sendJob.cancel()
        advanceUntilIdle()
        sendJob.join()

        // Cancellation outranks the recorded error end, so the user's own stop earns no badge.
        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, null) }
        verify(exactly = 0) { harness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    @Test
    fun `non-streaming turn reports the same outcomes`() = runTest {
        val successHarness = buildHarness(isStreaming = false)
        every { successHarness.sessionRepository.processNewMessage(session.id, any()) } returns flow {
            emit(ChatEvent.AssistantMessageSaved(assistantMessage(isComplete = true), parentMessage()).right())
            emit(ChatEvent.StreamCompleted.right())
        }

        useCase(successHarness).execute()

        verify(exactly = 1) { successHarness.registry.onTurnFinished(session.id, TurnOutcome.SUCCESS) }

        val failureHarness = buildHarness(isStreaming = false)
        every { failureHarness.sessionRepository.processNewMessage(session.id, any()) } returns flow {
            emit(
                ChatEvent.AssistantMessageSaved(
                    assistantMessage(isComplete = false, incompleteCause = AssistantMessageIncompleteCause.FAILED),
                    parentMessage()
                ).right()
            )
            emit(ChatEvent.StreamCompleted.right())
        }

        useCase(failureHarness).execute()

        verify(exactly = 1) { failureHarness.registry.onTurnFinished(session.id, TurnOutcome.FAILURE) }
    }

    // --- Awaiting-input sub-state around tool-call approvals ---

    @Test
    fun `deferred approval marks the session awaiting input until the user decides`() = runTest {
        val harness = buildHarness(isStreaming = true)
        val turnGate = CompletableDeferred<Unit>()
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ToolCallApprovalRequested(pendingToolCall).right())
            turnGate.await()
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        // The approval must be decided on the same use case instance that runs the turn.
        val sendUseCase = useCase(harness)
        val sendJob = launch { sendUseCase.execute() }
        advanceUntilIdle()

        verify(exactly = 1) { harness.registry.onTurnAwaitingInput(session.id, true) }

        sendUseCase.approveToolCall(pendingToolCall)
        verify(exactly = 1) { harness.registry.onTurnAwaitingInput(session.id, false) }

        turnGate.complete(Unit)
        advanceUntilIdle()
        sendJob.join()

        verify(exactly = 1) { harness.registry.onTurnFinished(session.id, null) }
    }

    @Test
    fun `auto-decided approval never marks the session awaiting input`() = runTest {
        val harness = buildHarness(
            isStreaming = true,
            preference = UserToolApprovalPreference(
                userId = 1L,
                toolDefinitionId = operatorTool.id,
                autoApprove = true,
                denialReason = null
            )
        )
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ToolCallApprovalRequested(pendingToolCall).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        useCase(harness).execute()

        verify(exactly = 0) { harness.registry.onTurnAwaitingInput(any(), any()) }
    }
}
