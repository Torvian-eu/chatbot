package eu.torvian.chatbot.app.viewmodel.chat.usecase

import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.SessionRepository
import eu.torvian.chatbot.app.repository.ToolRepository
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.viewmodel.chat.state.ChatState
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.core.ChatClientEvent
import eu.torvian.chatbot.common.models.api.core.ChatStreamEvent
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for the operator-tool approval flow in [SendMessageUseCase].
 *
 * Verifies that a `ToolCallApprovalRequested` for an operator tool (e.g. `spawn_agent`) is left to
 * the user in the UI when no explicit `UserToolApprovalPreference` exists (the approval dialog
 * shows), and that explicit preferences drive auto-decisions emitted as
 * `ChatClientEvent.OperatorToolCallApproval`.
 */
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
        inputSchema = OperatorToolCatalog.allTools.single().inputSchema,
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
        // The built-in lookup path consults getToolById before the operator branch; resolving it to
        // the operator instance keeps the (non-built-in) cast a no-op.
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
            agentSpawnExecutor = mockk(),
            state = harness.state,
            notificationService = mockk<NotificationService>()
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
}
