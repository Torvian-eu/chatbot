package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.service.api.AgentRoleApi
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

    @BeforeTest
    fun setup() {
        api = mockk()
        repository = DefaultAgentRoleRepository(api)
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
            CreateAgentRoleRequest(name = "translator", modelId = 1L, modelSettingsId = 2L)
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
            UpdateAgentRoleRequest(name = "writer-v2", modelId = 1L, modelSettingsId = 2L)
        )

        assertTrue(result.isRight())
        val state = repository.roles.value
        assertTrue(state is DataState.Success)
        assertEquals("writer-v2", state.data.single().name)
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
