package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ModelPresetDialogState
import eu.torvian.chatbot.app.repository.ModelPresetRepository
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.ModelSettingsRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/**
 * Tests for [ModelPresetsViewModel]: catalog loading, master-detail selection, dialog/form state,
 * draft-to-request mapping (including the round trip of a persisted settings-only reference) and
 * error notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelPresetsViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var presetRepository: ModelPresetRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var settingsRepository: ModelSettingsRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: ModelPresetsViewModel

    private val presetsFlow = MutableStateFlow<DataState<RepositoryError, List<ModelPresetDto>>>(DataState.Success(emptyList()))

    private fun preset(
        id: Long,
        name: String,
        modelId: Long? = 1L,
        modelSettingsId: Long? = 2L
    ) = ModelPresetDto(
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
        dispatcher = UnconfinedTestDispatcher()
        presetRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)

        every { presetRepository.presets } returns presetsFlow
        every { modelRepository.models } returns MutableStateFlow<DataState<RepositoryError, List<LLMModel>>>(DataState.Success(emptyList()))
        every { settingsRepository.allSettings } returns MutableStateFlow(DataState.Success(emptyList()))
        coEvery { presetRepository.loadPresets() } returns Either.Right(Unit)
        coEvery { modelRepository.loadModels() } returns Either.Right(Unit)
        coEvery { settingsRepository.loadAllSettings() } returns Either.Right(Unit)

        viewModel = ModelPresetsViewModel(
            modelPresetRepository = presetRepository,
            modelRepository = modelRepository,
            modelSettingsRepository = settingsRepository,
            notificationService = notificationService,
            uiDispatcher = dispatcher
        )
    }

    @AfterTest
    fun tearDown() {
        // Cancel the viewModel scope so no coroutine leaks across tests.
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `loadPresetsAndCatalogs - loads presets, models and settings`() = runTest(dispatcher) {
        viewModel.loadPresetsAndCatalogs()

        coVerify(exactly = 1) { presetRepository.loadPresets() }
        coVerify(exactly = 1) { modelRepository.loadModels() }
        coVerify(exactly = 1) { settingsRepository.loadAllSettings() }
    }

    @Test
    fun `loadPresetsAndCatalogs - failure notifies for the failing catalog`() = runTest(dispatcher) {
        coEvery { presetRepository.loadPresets() } returns Either.Left(RepositoryError.OtherError("boom"))

        viewModel.loadPresetsAndCatalogs()

        coVerify { notificationService.repositoryError(any<RepositoryError>(), any<String>()) }
    }

    @Test
    fun `selectPreset - resolves the selected preset from the stream`() = runTest(dispatcher) {
        val first = preset(1, "alpha")
        val second = preset(2, "beta")
        presetsFlow.value = DataState.Success(listOf(first, second))

        viewModel.selectPreset(second)
        assertEquals(second, awaitSelection(second))

        viewModel.selectPreset(null)
        assertNull(awaitSelection(null))
    }

    @Test
    fun `startAddingNewPreset - opens a fresh NEW draft`() = runTest(dispatcher) {
        viewModel.startAddingNewPreset()

        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ModelPresetDialogState.AddPreset)
        assertNull(dialogState.formState.presetId)
        assertEquals("", dialogState.formState.name)
        assertNull(dialogState.formState.modelId)
        assertNull(dialogState.formState.modelSettingsId)
    }

    @Test
    fun `startEditingPreset - opens an EDIT draft with both references`() = runTest(dispatcher) {
        val existing = preset(7, "primary", modelId = 1L, modelSettingsId = 2L)

        viewModel.startEditingPreset(existing)

        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ModelPresetDialogState.EditPreset)
        assertEquals(7L, dialogState.formState.presetId)
        assertEquals("primary", dialogState.formState.name)
        assertEquals(1L, dialogState.formState.modelId)
        assertEquals(2L, dialogState.formState.modelSettingsId)
    }

    @Test
    fun `savePreset - add - maps the draft to CreateModelPresetRequest and selects the result`() = runTest(dispatcher) {
        val created = preset(10, "primary", modelId = 1L, modelSettingsId = 2L)
        coEvery { presetRepository.createPreset(any()) } returns Either.Right(created)
        presetsFlow.value = DataState.Success(listOf(created))

        viewModel.startAddingNewPreset()
        viewModel.updatePresetForm { form ->
            form.copy(
                name = "primary",
                displayName = "Primary",
                description = "Main preset",
                modelId = 1L,
                modelSettingsId = 2L
            )
        }

        viewModel.savePreset()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            presetRepository.createPreset(
                match<CreateModelPresetRequest> { request ->
                    request.name == "primary" &&
                            request.displayName == "Primary" &&
                            request.description == "Main preset" &&
                            request.modelId == 1L &&
                            request.modelSettingsId == 2L
                }
            )
        }
        assertEquals(ModelPresetDialogState.None, viewModel.dialogState.value)
        assertEquals(created, awaitSelection(created))
    }

    @Test
    fun `savePreset - add - sends a model-only preset with a null settings reference`() = runTest(dispatcher) {
        coEvery { presetRepository.createPreset(any()) } returns Either.Right(preset(10, "model-only"))
        viewModel.startAddingNewPreset()
        viewModel.updatePresetForm { form -> form.copy(name = "model-only", modelId = 1L, modelSettingsId = null) }

        viewModel.savePreset()

        coVerify(exactly = 1) {
            presetRepository.createPreset(
                match<CreateModelPresetRequest> { request ->
                    request.modelId == 1L && request.modelSettingsId == null
                }
            )
        }
    }

    @Test
    fun `savePreset - edit - round-trips an untouched settings-only reference`() = runTest(dispatcher) {
        // The form gates the settings picker on a model, but the gate must not rewrite data: editing
        // the name of a preset that already carries a settings reference without a model keeps it.
        val existing = preset(7, "legacy", modelId = null, modelSettingsId = 2L)
        coEvery { presetRepository.updatePreset(7L, any()) } returns Either.Right(existing.copy(name = "renamed"))
        viewModel.startEditingPreset(existing)

        viewModel.updatePresetForm { form -> form.copy(name = "renamed") }

        viewModel.savePreset()

        coVerify(exactly = 1) {
            presetRepository.updatePreset(
                eq(7L),
                match<UpdateModelPresetRequest> { request ->
                    request.name == "renamed" && request.modelId == null && request.modelSettingsId == 2L
                }
            )
        }
    }

    @Test
    fun `savePreset - blank name - validates without calling the api`() = runTest(dispatcher) {
        viewModel.startAddingNewPreset()

        viewModel.savePreset()

        coVerify(exactly = 0) { presetRepository.createPreset(any()) }
        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ModelPresetDialogState.AddPreset)
        assertNotNull(dialogState.formState.errorMessage)
    }

    @Test
    fun `savePreset - failure - notifies and keeps the dialog open with the server message`() = runTest(dispatcher) {
        coEvery { presetRepository.createPreset(any()) } returns Either.Left(
            RepositoryError.OtherError("name already exists")
        )
        viewModel.startAddingNewPreset()
        viewModel.updatePresetForm { form -> form.copy(name = "primary") }

        viewModel.savePreset()

        coVerify { notificationService.repositoryError(any<RepositoryError>(), any<String>()) }
        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ModelPresetDialogState.AddPreset)
        assertTrue(dialogState.formState.errorMessage!!.contains("name already exists"))
    }

    @Test
    fun `deletePreset - success - clears the selection and closes the dialog`() = runTest(dispatcher) {
        val existing = preset(7, "primary")
        coEvery { presetRepository.deletePreset(7L) } returns Either.Right(Unit)
        presetsFlow.value = DataState.Success(listOf(existing))
        viewModel.selectPreset(existing)
        assertEquals(existing, awaitSelection(existing))
        viewModel.startDeletingPreset(existing)

        viewModel.deletePreset(7L)
        advanceUntilIdle()

        assertNull(awaitSelection(null))
        assertEquals(ModelPresetDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `deletePreset - failure - notifies and keeps the dialog open`() = runTest(dispatcher) {
        val existing = preset(7, "primary")
        coEvery { presetRepository.deletePreset(7L) } returns Either.Left(RepositoryError.OtherError("deletion failed"))
        viewModel.startDeletingPreset(existing)

        viewModel.deletePreset(7L)

        coVerify { notificationService.repositoryError(any<RepositoryError>(), any<String>()) }
        assertTrue(viewModel.dialogState.value is ModelPresetDialogState.DeletePreset)
    }

    @Test
    fun `cancelDialog - closes whichever dialog is open`() = runTest(dispatcher) {
        viewModel.startAddingNewPreset()

        viewModel.cancelDialog()

        assertEquals(ModelPresetDialogState.None, viewModel.dialogState.value)
    }

    /**
     * Waits for the derived selection to resolve to [expected] and returns the observed value.
     *
     * The derived selection is a `WhileSubscribed` StateFlow hosted in `viewModelScope` — i.e. on
     * `Dispatchers.Main`, which the test cannot drive — so a plain `value` read can lag behind a
     * selection change. Collecting until the expected state arrives keeps the assertions deterministic
     * without replacing the JVM-global Main dispatcher that the UI tests in this module rely on.
     *
     * @param expected The selection the test is waiting for (null for "nothing selected").
     * @return The observed selection, equal to [expected].
     */
    private suspend fun awaitSelection(expected: ModelPresetDto?): ModelPresetDto? =
        viewModel.selectedPreset.first { it == expected }
}
