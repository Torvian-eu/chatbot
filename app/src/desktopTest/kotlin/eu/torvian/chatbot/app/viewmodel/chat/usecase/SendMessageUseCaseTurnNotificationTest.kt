package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.right
import eu.torvian.chatbot.app.domain.TurnOutcome
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.events.TurnLifecycleTrigger
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.viewmodel.DefaultSessionSelectionController
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.InMemorySessionTurnStatusRegistry
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.core.ChatEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
import eu.torvian.chatbot.common.models.core.AssistantMessageErrorCode
import eu.torvian.chatbot.common.models.core.AssistantMessageIncompleteCause
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.tool.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the turn alerts an actual turn produces, wired end to end: [SendMessageUseCase] drives the
 * real [InMemorySessionTurnStatusRegistry], whose published triggers are read off the shared event bus.
 *
 * The bus buffers the published triggers, so these tests subscribe first and assert the exact set of
 * triggers a turn produced for both transports, including the endings and deferrals that must stay
 * silent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendMessageUseCaseTurnNotificationTest {

    /** Tool definition id shared by the approval fixtures. */
    private val TOOL_DEFINITION_ID = 9L

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private val role = AgentRoleDto(id = 5L, name = "writer", modelId = 1L, modelSettingsId = 2L)

    /**
     * Operator tool definition the approval path resolves from the cached tool list.
     *
     * @return Definition of the `spawn_agent` operator tool on the fixture tool id.
     */
    private fun operatorTool() = OperatorToolDefinition(
        id = TOOL_DEFINITION_ID,
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

    /**
     * Tool call left to the user for manual approval.
     *
     * @param id Identifier distinguishing parallel deferred calls.
     * @return Tool call awaiting approval on the harness session.
     */
    private fun pendingToolCall(id: Long = 42L) = ToolCall(
        id = id,
        messageId = 100L,
        toolDefinitionId = TOOL_DEFINITION_ID,
        toolName = "spawn_agent",
        input = "{}",
        output = null,
        status = ToolCallStatus.AWAITING_APPROVAL,
        errorMessage = null,
        denialReason = null,
        executedAt = now,
        durationMs = null
    )

    /**
     * Collaborators of the send path plus the event bus whose triggers are asserted.
     *
     * @property sessionRepository Strict mock whose message-processing flow is supplied per test.
     * @property toolRepository Tool cache and approval preferences used by the approval path.
     * @property state Chat state the send path reads.
     * @property registry Real registry the send path reports its lifecycle to; it publishes the
     *           triggers this test asserts.
     * @property eventBus Shared event bus capturing the published turn triggers.
     */
    private data class Harness(
        val sessionRepository: SessionRepository,
        val toolRepository: ToolRepository,
        val state: ChatState,
        val registry: InMemorySessionTurnStatusRegistry,
        val eventBus: EventBus
    )

    /**
     * Builds the send-path harness for the requested streaming mode.
     *
     * @param isStreaming Whether the settings profile streams, selecting the send path in the use case.
     * @param preference Stored approval preference, or `null` when approvals stay manual.
     * @param role Selected agent role, or `null` to simulate a session that cannot send.
     * @return Harness with mocked collaborators, a real registry, and the shared event bus.
     */
    private fun TestScope.buildHarness(
        isStreaming: Boolean,
        preference: UserToolApprovalPreference? = null,
        role: AgentRoleDto? = this@SendMessageUseCaseTurnNotificationTest.role
    ): Harness {
        val sessionRepository = mockk<SessionRepository>()
        val toolRepository = mockk<ToolRepository>()
        val state = mockk<ChatState>()

        every { state.currentSession } returns MutableStateFlow<ChatSession?>(session)
        every { state.currentAgentRole } returns MutableStateFlow(role)
        every { state.currentModel } returns MutableStateFlow<LLMModel?>(
            LLMModel(
                id = 1L,
                name = "gpt-4",
                providerId = 1L,
                active = true
            )
        )
        every { state.currentSettings } returns MutableStateFlow<ModelSettings?>(
            ChatModelSettings(id = 2L, modelId = 1L, name = "Chat profile", stream = isStreaming)
        )
        every { state.inputContent } returns MutableStateFlow("hello")
        every { state.replyTargetMessage } returns MutableStateFlow(null)
        every { state.pendingFileReferences } returns MutableStateFlow(emptyList())

        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(listOf(operatorTool())))
        every { toolRepository.toolApprovalPreferences } returns MutableStateFlow(
            DataState.Success(if (preference == null) emptyList() else listOf(preference))
        )

        val eventBus = EventBus()
        return Harness(
            sessionRepository = sessionRepository,
            toolRepository = toolRepository,
            state = state,
            // The registry observes session selection, so it shares the test scope and cannot
            // outlive the test.
            registry = InMemorySessionTurnStatusRegistry(
                DefaultSessionSelectionController(),
                eventBus,
                backgroundScope
            ),
            eventBus = eventBus
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

    /** Builds the user message a streaming start event refers to. */
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

    /**
     * Runs the send path while collecting the triggers it publishes.
     *
     * @param harness Harness to run against.
     * @param useCase Use case instance to execute, so approval decisions can target the same instance.
     * @param afterStart Work executed while the turn is still running, if any.
     * @return Triggers published by the turn, in publish order.
     */
    private suspend fun TestScope.runAndCollect(
        harness: Harness,
        useCase: SendMessageUseCase = useCase(harness),
        afterStart: suspend () -> Unit = {}
    ): List<TurnLifecycleTrigger> {
        val published = mutableListOf<TurnLifecycleTrigger>()
        backgroundScope.launch {
            harness.eventBus.events.filterIsInstance<TurnLifecycleTrigger>().collect { published.add(it) }
        }
        runCurrent()

        val sendJob = launch { useCase.execute() }
        advanceUntilIdle()
        afterStart()
        sendJob.join()
        advanceUntilIdle()
        runCurrent()

        return published
    }

    @Test
    fun `streaming success publishes exactly one success trigger`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(assistantMessage(isComplete = true)).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        val published = runAndCollect(harness)

        assertEquals(listOf(TurnLifecycleTrigger.TurnCompleted(session.id, TurnOutcome.SUCCESS)), published.toList())
    }

    @Test
    fun `streaming failure publishes exactly one failure trigger`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(
                ChatStreamEvent.AssistantMessageEnd(
                    assistantMessage(isComplete = false, incompleteCause = AssistantMessageIncompleteCause.FAILED)
                ).right()
            )
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        val published = runAndCollect(harness)

        assertEquals(listOf(TurnLifecycleTrigger.TurnCompleted(session.id, TurnOutcome.FAILURE)), published.toList())
    }

    @Test
    fun `non-streaming success publishes exactly one success trigger`() = runTest {
        val harness = buildHarness(isStreaming = false)
        every { harness.sessionRepository.processNewMessage(session.id, any()) } returns flow {
            emit(ChatEvent.AssistantMessageSaved(assistantMessage(isComplete = true), parentMessage()).right())
            emit(ChatEvent.StreamCompleted.right())
        }

        val published = runAndCollect(harness)

        assertEquals(listOf(TurnLifecycleTrigger.TurnCompleted(session.id, TurnOutcome.SUCCESS)), published.toList())
    }

    @Test
    fun `interrupted turn publishes no trigger`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
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

        val published = runAndCollect(harness)

        assertTrue(published.isEmpty(), "an interrupted turn must not alert")
    }

    @Test
    fun `cancelled turn publishes no trigger`() = runTest {
        val harness = buildHarness(isStreaming = true)
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.AssistantMessageStart(assistantMessage(isComplete = false), parentMessage()).right())
            emit(ChatStreamEvent.AssistantMessageEnd(assistantMessage(isComplete = true)).right())
            awaitCancellation()
        }
        val published = mutableListOf<TurnLifecycleTrigger>()
        backgroundScope.launch {
            harness.eventBus.events.filterIsInstance<TurnLifecycleTrigger>().collect { published.add(it) }
        }
        runCurrent()

        val sendJob = launch { useCase(harness).execute() }
        advanceUntilIdle()
        sendJob.cancel()
        sendJob.join()
        runCurrent()

        assertTrue(published.isEmpty(), "a cancelled turn must not alert despite the complete message")
    }

    @Test
    fun `turn that never starts publishes no trigger`() = runTest {
        val harness = buildHarness(isStreaming = true, role = null)

        val published = runAndCollect(harness)

        assertTrue(published.isEmpty(), "an early return before the turn starts must not alert")
    }

    @Test
    fun `deferred approval publishes exactly one awaiting trigger`() = runTest {
        val harness = buildHarness(isStreaming = true)
        val turnGate = CompletableDeferred<Unit>()
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ToolCallApprovalRequested(pendingToolCall()).right())
            turnGate.await()
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        // The approval must be decided on the same use case instance that runs the turn.
        val sendUseCase = useCase(harness)
        // Releasing the gate lets the turn finish without a terminal message, so the only trigger the
        // turn can produce is the approval deferral.
        val published = runAndCollect(harness, sendUseCase) { turnGate.complete(Unit) }

        assertEquals(
            listOf(TurnLifecycleTrigger.AwaitingApproval(session.id)),
            published.toList()
        )
    }

    @Test
    fun `parallel deferred calls in one awaiting phase publish a single trigger`() = runTest {
        val harness = buildHarness(isStreaming = true)
        val firstCall = pendingToolCall(id = 42L)
        val secondCall = pendingToolCall(id = 43L)
        val turnGate = CompletableDeferred<Unit>()
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ToolCallApprovalRequested(firstCall).right())
            emit(ChatStreamEvent.ToolCallApprovalRequested(secondCall).right())
            turnGate.await()
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        val published = runAndCollect(harness, useCase(harness)) { turnGate.complete(Unit) }

        // Only entering the awaiting state alerts; a burst of parallel deferrals must not fire a
        // burst of sounds in the same instant.
        assertEquals(
            listOf(TurnLifecycleTrigger.AwaitingApproval(session.id)),
            published.toList()
        )
    }

    @Test
    fun `auto-decided approval publishes no awaiting trigger`() = runTest {
        val harness = buildHarness(
            isStreaming = true,
            preference = UserToolApprovalPreference(
                userId = 1L,
                toolDefinitionId = TOOL_DEFINITION_ID,
                autoApprove = true,
                denialReason = null
            )
        )
        every { harness.sessionRepository.processNewMessageStreaming(session.id, any()) } returns flow {
            emit(ChatStreamEvent.ToolCallApprovalRequested(pendingToolCall()).right())
            emit(ChatStreamEvent.StreamCompleted.right())
        }

        val published = runAndCollect(harness)

        assertTrue(published.isEmpty(), "an auto-approved call never asks the user")
    }
}
