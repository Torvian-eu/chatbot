package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.service.api.AgentRoleApi
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.InstructionApi
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [DefaultAgentRoleRepository]: CRUD operations must keep the reactive [DataState] in sync.
 */
class DefaultAgentRoleRepositoryTest {

    private lateinit var api: AgentRoleApi
    private lateinit var projectRepository: ProjectRepository
    private lateinit var instructionApi: InstructionApi
    private lateinit var repository: DefaultAgentRoleRepository

    private fun role(id: Long, name: String) = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = 1L,
        modelSettingsId = 2L,
        tools = emptySet(),
        instructions = emptyList()
    )

    /**
     * Builds one instruction entry as a role payload reports it.
     *
     * @param id The instruction row id.
     * @return The reported entry.
     */
    private fun writerInstruction(id: Long) = eu.torvian.chatbot.common.models.agent.AgentInstructionDto(
        id = id,
        type = eu.torvian.chatbot.common.models.agent.AgentInstructionTypes.CUSTOM,
        name = "Tone",
        message = "Be concise"
    )

    @BeforeTest
    fun setup() {
        api = mockk()
        projectRepository = mockk()
        instructionApi = mockk()
        coEvery { projectRepository.loadProjects() } returns Either.Right(Unit)
        repository = DefaultAgentRoleRepository(api, projectRepository, instructionApi)
    }

    @Test
    fun `loadRoles - success updates state`() = runTest {
        val roles = listOf(role(1, "writer"), role(2, "coder"))
        coEvery { api.getAllRoles() } returns Either.Right(roles)

        val result = repository.loadRoles()

        assertTrue(result.isRight())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals(2, state.data.size)
    }

    @Test
    fun `loadRoles - failure updates state to error`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Left(
            eu.torvian.chatbot.app.service.api.ApiResourceError.UnknownError("boom", null)
        )

        val result = repository.loadRoles()

        assertTrue(result.isLeft())
        assertTrue(repository.roles.value is DataState.Error)
    }

    @Test
    fun `createRole - appends to state`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(emptyList())
        repository.loadRoles()

        val created = role(10, "translator")
        coEvery { api.createRole(any()) } returns Either.Right(created)

        val result = repository.createRole(
            CreateAgentRoleRequest(name = "translator", modelPresetId = 3L)
        )

        assertTrue(result.isRight())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals(listOf("translator"), state.data.map { it.name })
    }

    @Test
    fun `updateRole - replaces entry in state`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()

        val updated = role(1, "writer-v2")
        coEvery { api.updateRole(1L, any()) } returns Either.Right(updated)

        val result = repository.updateRole(
            1L,
            UpdateAgentRoleRequest(name = "writer-v2", modelPresetId = 3L)
        )

        assertTrue(result.isRight())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals("writer-v2", state.data.single().name)
    }

    @Test
    fun `setRoleDisabled - replaces entry in state with the new flag`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()

        val disabled = role(1, "writer").copy(disabled = true)
        coEvery { api.setRoleDisabled(1L, true) } returns Either.Right(disabled)

        val result = repository.setRoleDisabled(1L, disabled = true)

        assertTrue(result.isRight())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals(true, state.data.single().disabled)
        coVerify(exactly = 1) { api.setRoleDisabled(1L, true) }
    }

    @Test
    fun `setRoleDisabled - failure leaves state unchanged`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()

        coEvery { api.setRoleDisabled(1L, true) } returns Either.Left(
            eu.torvian.chatbot.app.service.api.ApiResourceError.UnknownError("boom", null)
        )

        val result = repository.setRoleDisabled(1L, disabled = true)

        assertTrue(result.isLeft())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals(false, state.data.single().disabled)
    }

    @Test
    fun `deleteRole - removes entry from state`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer"), role(2, "coder")))
        repository.loadRoles()

        coEvery { api.deleteRole(1L) } returns Either.Right(Unit)

        val result = repository.deleteRole(1L)

        assertTrue(result.isRight())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals(1, state.data.size)
        assertEquals("coder", state.data.single().name)
    }

    @Test
    fun `createRole - refreshes the project stream after success`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(emptyList())
        repository.loadRoles()

        coEvery { api.createRole(any()) } returns Either.Right(role(10, "translator"))

        val result = repository.createRole(
            CreateAgentRoleRequest(name = "translator", modelPresetId = 3L)
        )

        assertTrue(result.isRight())
        // The role may carry project membership; the project side of the cache is refreshed so
        // ProjectDto.agentRoleIds stays consistent.
        coVerify(exactly = 1) { projectRepository.loadProjects() }
    }

    @Test
    fun `updateRole - refreshes the project stream after success`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()

        coEvery { api.updateRole(1L, any()) } returns Either.Right(role(1, "writer-v2"))

        repository.updateRole(1L, UpdateAgentRoleRequest(name = "writer-v2", modelPresetId = 3L))

        coVerify(exactly = 1) { projectRepository.loadProjects() }
    }

    @Test
    fun `deleteRole - refreshes the project stream after success`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()

        coEvery { api.deleteRole(1L) } returns Either.Right(Unit)

        repository.deleteRole(1L)

        coVerify(exactly = 1) { projectRepository.loadProjects() }
    }

    @Test
    fun `role failure does not trigger a project refresh`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(emptyList())
        repository.loadRoles()

        coEvery { api.createRole(any()) } returns Either.Left(
            eu.torvian.chatbot.app.service.api.ApiResourceError.UnknownError("boom", null)
        )

        repository.createRole(CreateAgentRoleRequest(name = "x"))

        coVerify(exactly = 0) { projectRepository.loadProjects() }
    }

    @Test
    fun `assignInstruction - replaces the cached role with the response echo`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()
        val updated = role(1, "writer").copy(instructions = listOf(writerInstruction(7L)))
        coEvery { instructionApi.assignInstruction(1L, 7L) } returns Either.Right(updated)

        val result = repository.assignInstruction(roleId = 1L, instructionId = 7L)

        assertEquals(updated, result.getOrNull())
        // The echo is the role's state after the append, so the role stream needs no second read.
        assertEquals(listOf(7L), repository.roles.value.dataOrNull?.single()?.instructions?.map { it.id })
        coVerify(exactly = 1) { instructionApi.assignInstruction(1L, 7L) }
        // Linking an instruction changes no project membership.
        coVerify(exactly = 0) { projectRepository.loadProjects() }
    }

    @Test
    fun `assignInstruction - failure is wrapped in the operation context and leaves the cache alone`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer")))
        repository.loadRoles()
        coEvery { instructionApi.assignInstruction(1L, 7L) } returns
            Either.Left(ApiResourceError.UnknownError("boom", null))

        val result = repository.assignInstruction(roleId = 1L, instructionId = 7L)

        assertTrue(result.isLeft())
        assertTrue(repository.roles.value.dataOrNull?.single()?.instructions.orEmpty().isEmpty())
    }

    @Test
    fun `unassignInstruction - replaces the cached role with the response echo`() = runTest {
        coEvery { api.getAllRoles() } returns Either.Right(listOf(role(1, "writer").copy(instructions = listOf(writerInstruction(7L)))))
        repository.loadRoles()
        val updated = role(1, "writer")
        coEvery { instructionApi.unassignInstruction(1L, 7L) } returns Either.Right(updated)

        val result = repository.unassignInstruction(roleId = 1L, instructionId = 7L)

        assertEquals(updated, result.getOrNull())
        // The link is gone from the role while the instruction row itself is untouched here.
        assertTrue(repository.roles.value.dataOrNull?.single()?.instructions.orEmpty().isEmpty())
        coVerify(exactly = 1) { instructionApi.unassignInstruction(1L, 7L) }
    }

    @Test
    fun `assignInstruction - upserts the echo when the role is not cached yet`() = runTest {
        // The echo is authoritative, so a role missing from the cache (e.g. an unloaded stream) is
        // added instead of being dropped.
        val updated = role(5, "reviewer")
        coEvery { instructionApi.assignInstruction(5L, 7L) } returns Either.Right(updated)

        repository.assignInstruction(roleId = 5L, instructionId = 7L)

        assertEquals(listOf(5L), repository.roles.value.dataOrNull?.map { it.id })
    }

    @Test
    fun `loadRoles - deduplicates concurrent loads`() = runTest {
        val gate = CompletableDeferred<List<AgentRoleDto>>()
        coEvery { api.getAllRoles() } coAnswers { gate.await().right() }

        // First load runs until it suspends on the gate; the state is now Loading.
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.loadRoles() }

        // A second call while Loading must return immediately without a duplicate API request.
        val second = repository.loadRoles()
        assertTrue(second.isRight())

        gate.complete(listOf(role(1, "writer")))
        first.await()

        coVerify(exactly = 1) { api.getAllRoles() }
    }
}
