package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for the agent-role derivations in [ChatStateImpl]:
 * `currentAgentRole` resolves from the session's role id and the role list, and
 * `currentModel`/`currentSettings` resolve from the role's bundled ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateAgentRoleTest {

    private val role = AgentRoleDto(
        id = 5L,
        name = "writer",
        displayName = "Writer",
        description = "",
        modelId = 1L,
        modelSettingsId = 2L,
        tools = emptySet(),
        instructions = emptyList()
    )

    private val model = LLMModel(id = 1L, name = "gpt-4", providerId = 10L, active = true)
    private val settings = fakeChatSettings(id = 2L, modelId = 1L, name = "Chat profile")

    private fun chatSession(agentRoleId: Long?) = ChatSession(
        id = 100L,
        name = "Session",
        createdAt = kotlin.time.Instant.fromEpochSeconds(0),
        updatedAt = kotlin.time.Instant.fromEpochSeconds(0),
        groupId = null,
        agentRoleId = agentRoleId,
        currentLeafMessageId = null,
        messages = emptyList()
    )

    private fun sessionFlow(agentRoleId: Long?) =
        MutableStateFlow<DataState<RepositoryError, ChatSession>>(DataState.Success(chatSession(agentRoleId)))

    private fun createState(
        scope: TestScope,
        rolesFlow: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>>,
        sessionFlow: MutableStateFlow<DataState<RepositoryError, ChatSession>>
    ): ChatStateImpl {
        val sessionRepository = mockk<SessionRepository>()
        coEvery { sessionRepository.getSessionDetailsFlow(any()) } returns sessionFlow

        val settingsRepository = mockk<ModelSettingsRepository>()
        every { settingsRepository.allSettings } returns MutableStateFlow(
            DataState.Success(listOf(settings))
        )

        val modelRepository = mockk<ModelRepository>()
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(listOf(model)))

        val toolRepository = mockk<ToolRepository>()
        every { toolRepository.tools } returns MutableStateFlow(
            DataState.Success(emptyList())
        )

        val mcpRepository = mockk<LocalMCPServerRepository>()
        every { mcpRepository.servers } returns MutableStateFlow(
            DataState.Success(emptyList())
        )

        val agentRoleRepository = mockk<AgentRoleRepository>()
        every { agentRoleRepository.roles } returns rolesFlow

        return ChatStateImpl(
            sessionRepository = sessionRepository,
            modelSettingsRepository = settingsRepository,
            modelRepository = modelRepository,
            toolRepository = toolRepository,
            mcpServerRepository = mcpRepository,
            agentRoleRepository = agentRoleRepository,
            threadBuilder = DefaultThreadBuilder(),
            backgroundScope = scope.backgroundScope
        )
    }

    @Test
    fun `no role - current role model and settings are null`() = runTest(UnconfinedTestDispatcher()) {
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(role)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = null))

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertNull(state.currentAgentRole.value)
        assertNull(state.currentModel.value)
        assertNull(state.currentSettings.value)
    }

    @Test
    fun `role attached - resolves role model and settings`() = runTest(UnconfinedTestDispatcher()) {
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(role)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = 5L))

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertEquals(role, state.currentAgentRole.value)
        assertEquals(model, state.currentModel.value)
        assertEquals(settings, state.currentSettings.value)
    }

    @Test
    fun `role deleted server-side - resolves to null`() = runTest(UnconfinedTestDispatcher()) {
        // The session references role 5 but the role list no longer contains it.
        val rolesFlow = MutableStateFlow<DataState<RepositoryError, List<AgentRoleDto>>>(DataState.Success(emptyList()))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = 5L))

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertNull(state.currentAgentRole.value)
        assertNull(state.currentModel.value)
        assertNull(state.currentSettings.value)
    }

    @Test
    fun `availableAgentRoles excludes roles disabled for the current user`() = runTest(UnconfinedTestDispatcher()) {
        val enabled = role
        val disabledRole = role.copy(id = 6L, name = "retired")
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(enabled, disabledRole.copy(disabled = true))))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = null))

        backgroundScope.launch { state.availableAgentRoles.collect {} }
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        // Only the enabled role reaches the top-bar selector;
        // the disabled one stays in the (unfiltered) repository stream but drops out of the chat view.
        val available = assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value)
        assertEquals(listOf(5L), available.data.map { it.id })
        // The repository stream itself still carries both roles (settings tab reads it unfiltered).
        val unfiltered = assertIs<DataState.Success<List<AgentRoleDto>>>(rolesFlow.value)
        assertEquals(2, unfiltered.data.size)
    }

    @Test
    fun `session attached to a disabled role resolves inert like a deleted role`() = runTest(UnconfinedTestDispatcher()) {
        // The session references role 5, which the user disabled: currentAgentRole/currentModel/
        // currentSettings must all resolve to null ("No role" + composer gated), mirroring the
        // deleted-role derivation.
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(role.copy(disabled = true))))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = 5L))

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertNull(state.currentAgentRole.value)
        assertNull(state.currentModel.value)
        assertNull(state.currentSettings.value)
    }

    @Test
    fun `re-enabling a disabled attached role restores the derivation reactively`() = runTest(UnconfinedTestDispatcher()) {
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(role.copy(disabled = true))))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = 5L))

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()
        assertNull(state.currentAgentRole.value)

        // The settings switch re-enables the role: the shared repository stream re-emits with the
        // flag flipped, and the chat derivation restores the role without any manual reload.
        rolesFlow.value = DataState.Success(listOf(role.copy(disabled = false)))
        advanceUntilIdle()

        assertEquals(role, state.currentAgentRole.value)
        assertEquals(model, state.currentModel.value)
        assertEquals(settings, state.currentSettings.value)
    }

    private fun TestScope.startCollecting(state: ChatStateImpl) {
        // The derived flows use WhileSubscribed; subscribing here keeps the derivations alive.
        backgroundScope.launch { state.currentAgentRole.collect {} }
        backgroundScope.launch { state.currentModel.collect {} }
        backgroundScope.launch { state.currentSettings.collect {} }
    }
}

/**
 * Builds a plain ChatModelSettings for tests of the role-derivation flows.
 */
private fun fakeChatSettings(id: Long, modelId: Long, name: String): ModelSettings =
    eu.torvian.chatbot.common.models.llm.ChatModelSettings(
        id = id,
        modelId = modelId,
        name = name
    )
