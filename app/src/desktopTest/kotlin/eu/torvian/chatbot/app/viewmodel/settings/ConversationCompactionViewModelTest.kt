package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ModelRepository
import eu.torvian.chatbot.app.repository.ModelSettingsRepository
import eu.torvian.chatbot.app.repository.UserPreferenceRepository
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ConversationCompactionViewModel]: the draft enable toggle is persisted by save (a
 * temporary disable keeps the stored configuration row), the destructive delete removes the row, and
 * a stored disabled row stays fully editable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationCompactionViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var userPreferenceRepository: UserPreferenceRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var settingsRepository: ModelSettingsRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: ConversationCompactionViewModel

    private fun preference(
        modelId: Long? = 7L,
        settingsId: Long? = 8L,
        instruction: String = "Keep it short.",
        systemMessage: String? = null,
        summaryLabel: String = ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
        enabled: Boolean = true
    ) = ConversationCompactionPreference(
        modelId = modelId,
        settingsId = settingsId,
        instruction = instruction,
        systemMessage = systemMessage,
        summaryLabel = summaryLabel,
        thresholdTokens = 50_000L,
        enabled = enabled
    )

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        userPreferenceRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)

        every { userPreferenceRepository.compactionPreference } returns MutableStateFlow(null)
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(emptyList()))
        every { settingsRepository.allSettingsDetails } returns MutableStateFlow(DataState.Success(emptyList()))

        viewModel = ConversationCompactionViewModel(
            userPreferenceRepository = userPreferenceRepository,
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
    fun `save with toggle off persists enabled=false while keeping the configuration`() = runTest(dispatcher) {
        coEvery { userPreferenceRepository.setCompactionPreference(any()) } returns Either.Right(Unit)

        viewModel.applyStoredPreference(null)
        viewModel.selectModel(10L)
        viewModel.selectSettings(20L)
        viewModel.updateInstruction("Summarize faithfully; keep decisions, names, open questions.")
        viewModel.updateThresholdText("50000")
        viewModel.setEnabled(false)

        viewModel.save()

        coVerify(exactly = 1) {
            userPreferenceRepository.setCompactionPreference(
                match<ConversationCompactionPreference> { preference ->
                    preference.modelId == 10L &&
                        preference.settingsId == 20L &&
                        preference.instruction == "Summarize faithfully; keep decisions, names, open questions." &&
                        preference.thresholdTokens == 50_000L &&
                        !preference.enabled
                }
            )
        }
    }

    @Test
    fun `save with toggle on persists enabled=true`() = runTest(dispatcher) {
        coEvery { userPreferenceRepository.setCompactionPreference(any()) } returns Either.Right(Unit)

        viewModel.applyStoredPreference(null)
        viewModel.selectModel(10L)
        viewModel.selectSettings(20L)
        viewModel.updateInstruction("Summarize faithfully.")
        viewModel.setEnabled(true)

        viewModel.save()

        coVerify(exactly = 1) {
            userPreferenceRepository.setCompactionPreference(
                match<ConversationCompactionPreference> { preference -> preference.enabled }
            )
        }
    }

    @Test
    fun `save with a non-blank system message persists it trimmed`() = runTest(dispatcher) {
        coEvery { userPreferenceRepository.setCompactionPreference(any()) } returns Either.Right(Unit)

        viewModel.applyStoredPreference(null)
        viewModel.selectModel(10L)
        viewModel.selectSettings(20L)
        viewModel.updateInstruction("Summarize faithfully.")
        viewModel.updateSystemMessage("  Be neutral and complete.  ")
        viewModel.setEnabled(true)

        viewModel.save()

        coVerify(exactly = 1) {
            userPreferenceRepository.setCompactionPreference(
                match<ConversationCompactionPreference> { preference ->
                    preference.systemMessage == "Be neutral and complete."
                }
            )
        }
    }

    @Test
    fun `save with a blank system message persists null`() = runTest(dispatcher) {
        coEvery { userPreferenceRepository.setCompactionPreference(any()) } returns Either.Right(Unit)

        viewModel.applyStoredPreference(null)
        viewModel.selectModel(10L)
        viewModel.selectSettings(20L)
        viewModel.updateInstruction("Summarize faithfully.")
        viewModel.updateSystemMessage("   ")
        viewModel.setEnabled(true)

        viewModel.save()

        coVerify(exactly = 1) {
            userPreferenceRepository.setCompactionPreference(
                match<ConversationCompactionPreference> { preference -> preference.systemMessage == null }
            )
        }
    }

    @Test
    fun `save with a custom summary label persists it as entered`() = runTest(dispatcher) {
        coEvery { userPreferenceRepository.setCompactionPreference(any()) } returns Either.Right(Unit)

        viewModel.applyStoredPreference(null)
        viewModel.selectModel(10L)
        viewModel.selectSettings(20L)
        viewModel.updateInstruction("Summarize faithfully.")
        viewModel.updateSummaryLabel("Digest: ")
        viewModel.setEnabled(true)

        viewModel.save()

        coVerify(exactly = 1) {
            userPreferenceRepository.setCompactionPreference(
                match<ConversationCompactionPreference> { preference ->
                    preference.summaryLabel == "Digest: "
                }
            )
        }
    }

    @Test
    fun `save with a blank summary label persists the stable default`() = runTest(dispatcher) {
        coEvery { userPreferenceRepository.setCompactionPreference(any()) } returns Either.Right(Unit)

        viewModel.applyStoredPreference(null)
        viewModel.selectModel(10L)
        viewModel.selectSettings(20L)
        viewModel.updateInstruction("Summarize faithfully.")
        viewModel.updateSummaryLabel("   ")
        viewModel.setEnabled(true)

        viewModel.save()

        coVerify(exactly = 1) {
            userPreferenceRepository.setCompactionPreference(
                match<ConversationCompactionPreference> { preference ->
                    preference.summaryLabel == ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL
                }
            )
        }
    }

    @Test
    fun `save with missing model validates without calling the api`() = runTest(dispatcher) {
        viewModel.applyStoredPreference(null)
        viewModel.updateInstruction("Only an instruction; no model or settings selected.")

        viewModel.save()

        coVerify(exactly = 0) { userPreferenceRepository.setCompactionPreference(any()) }
        assertTrue(viewModel.validationErrors.value.isNotEmpty())
    }

    @Test
    fun `load calls loadModels so the observed models flow is populated`() = runTest(dispatcher) {
        // Regression: loading the details flow instead would leave `models` idle and the tab would
        // show "No accessible models" until another screen (e.g. loading a chat session) loaded it.
        coEvery { modelRepository.loadModels() } returns Either.Right(Unit)
        coEvery { settingsRepository.loadAllSettingsDetails() } returns Either.Right(Unit)
        coEvery { userPreferenceRepository.syncPreferences() } returns Either.Right(Unit)

        viewModel.load()

        coVerify(exactly = 1) { modelRepository.loadModels() }
        coVerify(exactly = 0) { modelRepository.loadModelsDetails() }
    }

    @Test
    fun `applying a stored disabled row keeps the form editable with the toggle off`() {
        viewModel.applyStoredPreference(
            preference(
                enabled = false,
                systemMessage = "Be neutral and complete.",
                summaryLabel = "Digest: "
            )
        )

        assertFalse(viewModel.draftEnabled.value)
        assertEquals(7L, viewModel.selectedModelId.value)
        assertEquals(8L, viewModel.selectedSettingsId.value)
        assertEquals("Keep it short.", viewModel.instruction.value)
        assertEquals("Be neutral and complete.", viewModel.systemMessage.value)
        assertEquals("Digest: ", viewModel.summaryLabel.value)
        assertEquals("50000", viewModel.thresholdText.value)
    }

    @Test
    fun `clear deletes the row entirely and resets the draft to disabled defaults`() = runTest(dispatcher) {
        viewModel.applyStoredPreference(preference(enabled = true))
        viewModel.setEnabled(true)
        coEvery { userPreferenceRepository.clearCompactionPreference() } returns Either.Right(Unit)

        viewModel.clear()

        coVerify(exactly = 1) { userPreferenceRepository.clearCompactionPreference() }
        assertFalse(viewModel.draftEnabled.value)
        assertNull(viewModel.selectedModelId.value)
        assertNull(viewModel.selectedSettingsId.value)
        assertEquals("", viewModel.instruction.value)
        assertEquals("", viewModel.systemMessage.value)
        assertEquals(
            ConversationCompactionPreference.DEFAULT_COMPACTED_SUMMARY_LABEL,
            viewModel.summaryLabel.value
        )
        assertEquals(
            ConversationCompactionPreference.DEFAULT_COMPACTION_THRESHOLD_TOKENS.toString(),
            viewModel.thresholdText.value
        )
    }
}