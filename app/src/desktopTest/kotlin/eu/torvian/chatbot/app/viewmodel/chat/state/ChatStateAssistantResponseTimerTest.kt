package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Verifies that [ChatStateImpl] derives its elapsed-time measurement from turn transitions and clears it on a
 * reset, so a reused slot cannot display another session's value.
 */
class ChatStateAssistantResponseTimerTest {

    private val model = LLMModel(id = 1L, name = "gpt-4", providerId = 10L, active = true)
    private val settings: ModelSettings = ChatModelSettings(id = 2L, modelId = 1L, name = "Chat profile")

    /** Verifies a freshly created state exposes no measurement. */
    @Test
    fun `a fresh state is hidden`() = runTest {
        val state = createState(this)

        assertIs<AssistantResponseTimerState.Hidden>(state.assistantResponseTimer.value)
    }

    /** Verifies entering the running state starts a measurement. */
    @Test
    fun `starting a turn begins the measurement`() = runTest {
        val state = createState(this)

        state.setTurnExecutionState(TurnExecutionState.RUNNING)

        assertIs<AssistantResponseTimerState.Running>(state.assistantResponseTimer.value)
    }

    /** Verifies the end of a turn freezes a non-negative elapsed value. */
    @Test
    fun `ending a turn freezes the elapsed time`() = runTest {
        val state = createState(this)
        state.setTurnExecutionState(TurnExecutionState.RUNNING)

        state.setTurnExecutionState(TurnExecutionState.IDLE)

        val frozen = assertIs<AssistantResponseTimerState.Frozen>(state.assistantResponseTimer.value)
        assertTrue(frozen.elapsed >= Duration.ZERO, "elapsed must never be negative but was ${frozen.elapsed}")
    }

    /** Verifies a repeated idle write cannot overwrite the frozen measurement. */
    @Test
    fun `a repeated idle write keeps the frozen value`() = runTest {
        val state = createState(this)
        state.setTurnExecutionState(TurnExecutionState.RUNNING)
        state.setTurnExecutionState(TurnExecutionState.IDLE)
        val frozen = state.assistantResponseTimer.value

        state.setTurnExecutionState(TurnExecutionState.IDLE)

        assertEquals(frozen, state.assistantResponseTimer.value)
    }

    /** Verifies an intermediate state keeps counting rather than freezing. */
    @Test
    fun `pausing keeps the measurement running`() = runTest {
        val state = createState(this)
        state.setTurnExecutionState(TurnExecutionState.RUNNING)
        val started = state.assistantResponseTimer.value

        state.setTurnExecutionState(TurnExecutionState.PAUSING)
        state.setTurnExecutionState(TurnExecutionState.STOPPING)

        assertEquals(started, state.assistantResponseTimer.value)
    }

    /** Verifies a reset discards the measurement together with the turn state. */
    @Test
    fun `reset clears the measurement`() = runTest {
        val state = createState(this)
        state.setTurnExecutionState(TurnExecutionState.RUNNING)
        state.setTurnExecutionState(TurnExecutionState.IDLE)

        state.resetState()

        assertIs<AssistantResponseTimerState.Hidden>(state.assistantResponseTimer.value)
    }

    /**
     * Builds a state with placeholder repositories; the timer derives only from turn transitions, so no
     * repository data is required.
     *
     * @param scope Test scope supplying the long-lived background scope of the state.
     * @return A state whose repositories carry empty successful data.
     */
    private fun createState(scope: TestScope): ChatStateImpl {
        val session = ChatSession(
            id = 100L,
            name = "Session",
            createdAt = Instant.fromEpochSeconds(0),
            updatedAt = Instant.fromEpochSeconds(0),
            groupId = null,
            agentRoleId = null,
            currentLeafMessageId = null,
            messages = emptyList(),
            projectId = null
        )

        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.getSessionDetailsFlow(any()) } returns
                MutableStateFlow(DataState.Success(session))

        val settingsRepository = mockk<ModelSettingsRepository>()
        every { settingsRepository.allSettings } returns MutableStateFlow(DataState.Success(listOf(settings)))

        val modelRepository = mockk<ModelRepository>()
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(listOf(model)))

        val toolRepository = mockk<ToolRepository>()
        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(emptyList()))

        val mcpServerRepository = mockk<LocalMCPServerRepository>()
        every { mcpServerRepository.servers } returns MutableStateFlow(DataState.Success(emptyList()))

        val agentRoleRepository = mockk<AgentRoleRepository>()
        every { agentRoleRepository.roles } returns MutableStateFlow(DataState.Success(emptyList()))

        val projectRepository = mockk<ProjectRepository>()
        every { projectRepository.projects } returns MutableStateFlow(DataState.Success(emptyList()))

        return ChatStateImpl(
            sessionRepository = sessionRepository,
            modelSettingsRepository = settingsRepository,
            modelRepository = modelRepository,
            toolRepository = toolRepository,
            mcpServerRepository = mcpServerRepository,
            agentRoleRepository = agentRoleRepository,
            projectRepository = projectRepository,
            threadBuilder = DefaultThreadBuilder(),
            backgroundScope = scope.backgroundScope
        )
    }
}
