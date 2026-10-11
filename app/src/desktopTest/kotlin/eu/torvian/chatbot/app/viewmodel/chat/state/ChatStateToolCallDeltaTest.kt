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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies that [ChatStateImpl] keeps one tool-call-delta instant per assistant message and discards it when the
 * turn it belongs to stops streaming, so the indicator can never outlive that turn.
 */
class ChatStateToolCallDeltaTest {

    private val model = LLMModel(id = 1L, name = "gpt-4", providerId = 10L, active = true)
    private val settings: ModelSettings = ChatModelSettings(id = 2L, modelId = 1L, name = "Chat profile")

    /** Verifies a fresh state records nothing, so no message can show the indicator before the first delta. */
    @Test
    fun `a fresh state records no delta`() = runTest {
        val state = createState(this)

        assertEquals(emptyMap(), state.lastToolCallDeltaAt.value)
    }

    /** Verifies a note records the arrival for exactly the message that produced the delta. */
    @Test
    fun `a note records one instant for the message`() = runTest {
        val state = createState(this)

        state.noteToolCallDelta(7L)

        val record = state.lastToolCallDeltaAt.value
        assertEquals(setOf(7L), record.keys)
    }

    /** Verifies the window rolls: a later note replaces the message's instant instead of adding an entry. */
    @Test
    fun `a second note keeps a single entry`() = runTest {
        val state = createState(this)
        state.noteToolCallDelta(7L)
        val first = state.lastToolCallDeltaAt.value.getValue(7L)

        state.noteToolCallDelta(7L)

        val record = state.lastToolCallDeltaAt.value
        assertEquals(1, record.size)
        assertTrue(record.getValue(7L) >= first, "a later note must not move the record backwards")
    }

    /** Verifies messages are tracked independently. */
    @Test
    fun `notes for different messages coexist`() = runTest {
        val state = createState(this)

        state.noteToolCallDelta(7L)
        state.noteToolCallDelta(8L)

        assertEquals(setOf(7L, 8L), state.lastToolCallDeltaAt.value.keys)
    }

    /** Verifies forgetting removes only the listed messages and tolerates ids that carry no record. */
    @Test
    fun `forgetting removes the listed messages and tolerates unknown ids`() = runTest {
        val state = createState(this)
        state.noteToolCallDelta(7L)
        state.noteToolCallDelta(8L)

        state.forgetToolCallDeltas(setOf(7L, 999L))

        assertEquals(setOf(8L), state.lastToolCallDeltaAt.value.keys)
    }

    /** Verifies the blanket clear empties the whole record. */
    @Test
    fun `forgetting all empties the record`() = runTest {
        val state = createState(this)
        state.noteToolCallDelta(7L)
        state.noteToolCallDelta(8L)

        state.forgetAllToolCallDeltas()

        assertEquals(emptyMap(), state.lastToolCallDeltaAt.value)
    }

    /** Verifies any turn state other than running discards the record of the construction it was streaming. */
    @Test
    fun `leaving the running turn state clears the record`() = runTest {
        for (turnState in listOf(TurnExecutionState.IDLE, TurnExecutionState.PAUSING, TurnExecutionState.STOPPING)) {
            val state = createState(this)
            state.setTurnExecutionState(TurnExecutionState.RUNNING)
            state.noteToolCallDelta(7L)

            state.setTurnExecutionState(turnState)

            assertEquals(emptyMap(), state.lastToolCallDeltaAt.value, "expected a clear for $turnState")
        }
    }

    /** Verifies a repeated running write keeps the record, since the turn it describes is still streaming. */
    @Test
    fun `a repeated running write keeps the record`() = runTest {
        val state = createState(this)
        state.setTurnExecutionState(TurnExecutionState.RUNNING)
        state.noteToolCallDelta(7L)

        state.setTurnExecutionState(TurnExecutionState.RUNNING)

        assertEquals(setOf(7L), state.lastToolCallDeltaAt.value.keys)
    }

    /** Verifies a reset discards the record so a reused session slot cannot display it. */
    @Test
    fun `reset clears the record`() = runTest {
        val state = createState(this)
        state.noteToolCallDelta(7L)

        state.resetState()

        assertEquals(emptyMap(), state.lastToolCallDeltaAt.value)
    }

    /**
     * Builds a state with placeholder repositories; the delta record needs no repository data.
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
