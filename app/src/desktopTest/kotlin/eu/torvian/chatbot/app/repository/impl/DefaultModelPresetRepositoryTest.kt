package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ModelPresetApi
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
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
import kotlin.time.Instant

/**
 * Tests for [DefaultModelPresetRepository]: CRUD keeps the reactive [DataState] in sync, the cache is
 * held name-ascending (U-24), and every successful mutation refreshes the role stream (presets →
 * roles).
 */
class DefaultModelPresetRepositoryTest {

    private lateinit var api: ModelPresetApi
    private lateinit var agentRoleRepository: AgentRoleRepository
    private lateinit var repository: DefaultModelPresetRepository

    private fun preset(id: Long, name: String, modelId: Long? = 1L, modelSettingsId: Long? = 2L) =
        ModelPresetDto(
            id = id,
            name = name,
            displayName = null,
            description = "",
            modelId = modelId,
            modelSettingsId = modelSettingsId,
            createdAt = Instant.fromEpochSeconds(id),
            updatedAt = Instant.fromEpochSeconds(id)
        )

    @BeforeTest
    fun setup() {
        api = mockk()
        agentRoleRepository = mockk()
        coEvery { agentRoleRepository.loadRoles() } returns Either.Right(Unit)
        repository = DefaultModelPresetRepository(api, agentRoleRepository)
    }

    @Test
    fun `loadPresets - success updates state in name-ascending order`() = runTest {
        // The server returns id-ascending rows; the client owns the display order (U-24).
        val presets = listOf(
            preset(1, "zeta"),
            preset(2, "alpha"),
            preset(3, "middle")
        )
        coEvery { api.getAllPresets() } returns Either.Right(presets)

        val result = repository.loadPresets()

        assertTrue(result.isRight())
        val state = repository.presets.value
        assertTrue(state is DataState.Success)
        assertEquals(listOf("alpha", "middle", "zeta"), state.data.map { it.name })
    }

    @Test
    fun `loadPresets - failure updates state to error`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        val result = repository.loadPresets()

        assertTrue(result.isLeft())
        assertTrue(repository.presets.value is DataState.Error)
    }

    @Test
    fun `loadPresetDetails - upserts an existing preset and appends a new one`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "primary")))
        repository.loadPresets()

        val refreshed = preset(1, "primary renamed", modelSettingsId = 9L)
        coEvery { api.getPresetById(1L) } returns Either.Right(refreshed)
        repository.loadPresetDetails(1L)
        assertEquals("primary renamed", repository.presets.value.dataOrNull?.single()?.name)
        assertEquals(9L, repository.presets.value.dataOrNull?.single()?.modelSettingsId)

        val added = preset(3, "second")
        coEvery { api.getPresetById(3L) } returns Either.Right(added)
        repository.loadPresetDetails(3L)
        assertEquals(2, repository.presets.value.dataOrNull?.size)
    }

    @Test
    fun `createPreset - appends and re-sorts by name`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "middle")))
        repository.loadPresets()

        coEvery { api.createPreset(any()) } returns Either.Right(preset(10, "alpha"))

        val result = repository.createPreset(CreateModelPresetRequest(name = "alpha"))

        assertTrue(result.isRight())
        val state = repository.presets.value
        assertTrue(state is DataState.Success)
        // The new row sorts before the existing one, proving the append path keeps the invariant.
        assertEquals(listOf("alpha", "middle"), state.data.map { it.name })
    }

    @Test
    fun `updatePreset - replaces entry and re-sorts by name`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "alpha"), preset(2, "middle")))
        repository.loadPresets()

        coEvery { api.updatePreset(1L, any()) } returns Either.Right(preset(1, "zeta"))

        val result = repository.updatePreset(1L, UpdateModelPresetRequest(name = "zeta"))

        assertTrue(result.isRight())
        val state = repository.presets.value
        assertTrue(state is DataState.Success)
        assertEquals(listOf("middle", "zeta"), state.data.map { it.name })
    }

    @Test
    fun `deletePreset - removes entry from state`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "alpha"), preset(2, "middle")))
        repository.loadPresets()

        coEvery { api.deletePreset(1L) } returns Either.Right(Unit)

        val result = repository.deletePreset(1L)

        assertTrue(result.isRight())
        val state = repository.presets.value
        assertTrue(state is DataState.Success)
        assertEquals(listOf("middle"), state.data.map { it.name })
    }

    @Test
    fun `createPreset - refreshes the role stream after success`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(emptyList())
        repository.loadPresets()

        coEvery { api.createPreset(any()) } returns Either.Right(preset(10, "alpha"))

        val result = repository.createPreset(CreateModelPresetRequest(name = "alpha"))

        assertTrue(result.isRight())
        // An unrefreshed role row may already reference the preset, so the derived role data is
        // reloaded (one-directional presets → roles edge).
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `updatePreset - refreshes the role stream after success`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "alpha")))
        repository.loadPresets()

        coEvery { api.updatePreset(1L, any()) } returns Either.Right(preset(1, "zeta"))

        repository.updatePreset(1L, UpdateModelPresetRequest(name = "zeta"))

        // Re-pointing a preset changes the derived model/settings of every bound role.
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `deletePreset - refreshes the role stream after success`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "alpha")))
        repository.loadPresets()

        coEvery { api.deletePreset(1L) } returns Either.Right(Unit)

        repository.deletePreset(1L)

        // Bound roles survive with a nulled preset reference and nulled derived ids.
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `mutation failure does not trigger a role refresh`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(emptyList())
        repository.loadPresets()

        coEvery { api.createPreset(any()) } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        repository.createPreset(CreateModelPresetRequest(name = "alpha"))

        assertTrue(repository.presets.value is DataState.Success)
        assertEquals(0, repository.presets.value.dataOrNull?.size)
        coVerify(exactly = 0) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `loadPresetDetails - failure leaves state unchanged and does not refresh roles`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "alpha")))
        repository.loadPresets()

        coEvery { api.getPresetById(1L) } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        val result = repository.loadPresetDetails(1L)

        assertTrue(result.isLeft())
        assertEquals(listOf("alpha"), repository.presets.value.dataOrNull?.map { it.name })
        coVerify(exactly = 0) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `loadPresets - deduplicates concurrent loads`() = runTest {
        val gate = CompletableDeferred<List<ModelPresetDto>>()
        coEvery { api.getAllPresets() } coAnswers { gate.await().right() }

        // First load runs until it suspends on the gate; the state is now Loading.
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.loadPresets() }

        // A second call while Loading must return immediately without a duplicate API request.
        val second = repository.loadPresets()
        assertTrue(second.isRight())

        gate.complete(listOf(preset(1, "alpha")))
        first.await()

        coVerify(exactly = 1) { api.getAllPresets() }
    }

    @Test
    fun `role stream refresh never re-enters the preset api`() = runTest {
        // Guards the acyclicity of the presets → roles edge: the role repository is only asked to
        // reload; this repository never reacts to the role stream.
        coEvery { api.getAllPresets() } returns Either.Right(emptyList())
        repository.loadPresets()

        coEvery { api.createPreset(any()) } returns Either.Right(preset(10, "alpha"))
        repository.createPreset(CreateModelPresetRequest(name = "alpha"))

        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        coVerify(exactly = 1) { api.getAllPresets() }
    }

    @Test
    fun `agent role repository is unused by read paths`() = runTest {
        coEvery { api.getAllPresets() } returns Either.Right(listOf(preset(1, "alpha")))
        coEvery { api.getPresetById(1L) } returns Either.Right(preset(1, "alpha"))

        repository.loadPresets()
        repository.loadPresetDetails(1L)

        coVerify(exactly = 0) { agentRoleRepository.loadRoles() }
    }
}
