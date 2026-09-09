package eu.torvian.chatbot.app.viewmodel.chat.state

import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.core.ChatSession
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.project.ProjectDto
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
import kotlin.time.Instant

/**
 * Tests for the agent-role and project derivations in [ChatStateImpl]:
 * `currentAgentRole` resolves from the session's role id and the role list,
 * `currentModel`/`currentSettings` resolve from the role's bundled ids, the available-role stream
 * applies the Session Legality Invariant project filter, and `currentProject`/`projectsById`
 * resolve the session's selected project.
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

    private val project = ProjectDto(
        id = 50L,
        name = "Research",
        description = "",
        createdAt = Instant.fromEpochSeconds(0),
        agentRoleIds = setOf(5L)
    )

    private val model = LLMModel(id = 1L, name = "gpt-4", providerId = 10L, active = true)
    private val settings = fakeChatSettings(id = 2L, modelId = 1L, name = "Chat profile")

    private fun chatSession(agentRoleId: Long?, projectId: Long? = null) = ChatSession(
        id = 100L,
        name = "Session",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
        groupId = null,
        agentRoleId = agentRoleId,
        currentLeafMessageId = null,
        messages = emptyList(),
        projectId = projectId
    )

    private fun sessionFlow(agentRoleId: Long?, projectId: Long? = null) =
        MutableStateFlow<DataState<RepositoryError, ChatSession>>(DataState.Success(chatSession(agentRoleId, projectId)))

    private fun createState(
        scope: TestScope,
        rolesFlow: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>>,
        sessionFlow: MutableStateFlow<DataState<RepositoryError, ChatSession>>,
        projectsFlow: StateFlow<DataState<RepositoryError, List<ProjectDto>>> = MutableStateFlow(DataState.Success(emptyList()))
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

        val projectRepository = mockk<ProjectRepository>()
        every { projectRepository.projects } returns projectsFlow

        return ChatStateImpl(
            sessionRepository = sessionRepository,
            modelSettingsRepository = settingsRepository,
            modelRepository = modelRepository,
            toolRepository = toolRepository,
            mcpServerRepository = mcpRepository,
            agentRoleRepository = agentRoleRepository,
            projectRepository = projectRepository,
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

    @Test
    fun `availableAgentRoles offers only roles of the selected project`() = runTest(UnconfinedTestDispatcher()) {
        // Session has project 50 selected; only roles whose single projectId equals 50 are legal.
        val inProject = role.copy(id = 5L, projectId = 50L)
        val otherProject = role.copy(id = 6L, name = "other", projectId = 60L)
        val unassociated = role.copy(id = 7L, name = "free", projectId = null)
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(inProject, otherProject, unassociated)))
        // The session's project must resolve in the project list for the project filter to apply.
        val projectsFlow = MutableStateFlow(DataState.Success(listOf(project)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = null, projectId = 50L), projectsFlow)

        backgroundScope.launch { state.availableAgentRoles.collect {} }
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        val available = assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value)
        assertEquals(listOf(5L), available.data.map { it.id })
    }

    @Test
    fun `availableAgentRoles treats an unresolvable session project as no project`() = runTest(UnconfinedTestDispatcher()) {
        // The session references project 999, which is absent from the loaded project list (deleted
        // server-side): currentProject renders inert "No project", so the role dropdown must offer
        // only unassociated roles instead of an empty list.
        val inProject = role.copy(id = 5L, projectId = 50L)
        val unassociated = role.copy(id = 7L, name = "free", projectId = null)
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(inProject, unassociated)))
        val projectsFlow = MutableStateFlow(DataState.Success(listOf(project)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = null, projectId = 999L), projectsFlow)

        backgroundScope.launch { state.availableAgentRoles.collect {} }
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        val available = assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value)
        assertEquals(listOf(7L), available.data.map { it.id })
    }

    @Test
    fun `availableAgentRoles offers only unassociated roles when no project is selected`() = runTest(UnconfinedTestDispatcher()) {
        val inProject = role.copy(id = 5L, projectId = 50L)
        val unassociated = role.copy(id = 7L, name = "free", projectId = null)
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(inProject, unassociated)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = null, projectId = null))

        backgroundScope.launch { state.availableAgentRoles.collect {} }
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        val available = assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value)
        assertEquals(listOf(7L), available.data.map { it.id })
    }

    @Test
    fun `availableAgentRoles drops disabled roles even when they belong to the selected project`() = runTest(UnconfinedTestDispatcher()) {
        val enabled = role.copy(id = 5L, projectId = 50L)
        val disabledLegal = role.copy(id = 6L, name = "retired", projectId = 50L, disabled = true)
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(enabled, disabledLegal)))
        // The session's project must resolve in the project list for the project filter to apply.
        val projectsFlow = MutableStateFlow(DataState.Success(listOf(project)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = null, projectId = 50L), projectsFlow)

        backgroundScope.launch { state.availableAgentRoles.collect {} }
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        val available = assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value)
        assertEquals(listOf(5L), available.data.map { it.id })
    }

    @Test
    fun `session attached to a role outside the selected project resolves inert`() = runTest(UnconfinedTestDispatcher()) {
        // Drift: the session references role 5 while project 50 is selected, but role 5 is not a
        // member of 50. The role drops out of availableAgentRoles, so currentAgentRole is null and
        // the composer stays gated, mirroring the deleted/disabled-role inert UX.
        val otherProjectRole = role.copy(id = 5L, projectId = 60L)
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(otherProjectRole)))
        val state = createState(this, rolesFlow, sessionFlow(agentRoleId = 5L, projectId = 50L))

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertNull(state.currentAgentRole.value)
        assertNull(state.currentModel.value)
        assertNull(state.currentSettings.value)
    }

    @Test
    fun `switching the session project re-filters offered roles reactively`() = runTest(UnconfinedTestDispatcher()) {
        val writer = role.copy(id = 5L, projectId = 50L)
        val reader = role.copy(id = 6L, name = "reader", projectId = 60L)
        val rolesFlow = MutableStateFlow(DataState.Success(listOf(writer, reader)))
        val sessionFlow = sessionFlow(agentRoleId = null, projectId = 50L)
        // Both projects resolve in the project list, so the filter follows the session's project.
        val projectsFlow = MutableStateFlow(
            DataState.Success(listOf(project, project.copy(id = 60L, name = "Other")))
        )
        val state = createState(this, rolesFlow, sessionFlow, projectsFlow)

        backgroundScope.launch { state.availableAgentRoles.collect {} }
        state.setActiveSessionId(100L)
        advanceUntilIdle()
        assertEquals(
            listOf(5L),
            assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value).data.map { it.id }
        )

        // The project switch mutates the cached session (response-driven repository update); the
        // derivation must re-filter without any manual load.
        sessionFlow.value = DataState.Success(chatSession(agentRoleId = null, projectId = 60L))
        advanceUntilIdle()
        assertEquals(
            listOf(6L),
            assertIs<DataState.Success<List<AgentRoleDto>>>(state.availableAgentRoles.value).data.map { it.id }
        )
    }

    @Test
    fun `currentProject resolves from the session project and projectsById is a lookup map`() = runTest(UnconfinedTestDispatcher()) {
        val projectsFlow = MutableStateFlow(
            DataState.Success(
                listOf(project, project.copy(id = 51L, name = "Other"))
            )
        )
        val state = createState(this, rolesFlow = MutableStateFlow(DataState.Success(listOf(role))), sessionFlow = sessionFlow(agentRoleId = 5L, projectId = 50L), projectsFlow = projectsFlow)

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertEquals(project, state.currentProject.value)
        assertEquals(setOf(50L, 51L), state.projectsById.value.keys)
        assertEquals("Other", state.projectsById.value[51L]?.name)
    }

    @Test
    fun `currentProject is null when no project is selected or the project is unknown`() = runTest(UnconfinedTestDispatcher()) {
        val state = createState(
            this,
            rolesFlow = MutableStateFlow(DataState.Success(listOf(role))),
            sessionFlow = sessionFlow(agentRoleId = 5L, projectId = 999L), // Unknown project id (drift)
            projectsFlow = MutableStateFlow(DataState.Success(listOf(project)))
        )

        startCollecting(state)
        state.setActiveSessionId(100L)
        advanceUntilIdle()

        assertNull(state.currentProject.value)
    }

    private fun TestScope.startCollecting(state: ChatStateImpl) {
        // The derived flows use WhileSubscribed; subscribing here keeps the derivations alive.
        backgroundScope.launch { state.currentAgentRole.collect {} }
        backgroundScope.launch { state.currentModel.collect {} }
        backgroundScope.launch { state.currentSettings.collect {} }
        backgroundScope.launch { state.currentProject.collect {} }
        backgroundScope.launch { state.projectsById.collect {} }
        backgroundScope.launch { state.availableProjects.collect {} }
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
