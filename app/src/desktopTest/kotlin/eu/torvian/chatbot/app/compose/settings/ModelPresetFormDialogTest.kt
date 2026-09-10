package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.app.domain.contracts.ModelPresetFormState
import eu.torvian.chatbot.app.domain.contracts.createEmptyModelPresetForm
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [ModelPresetFormDialog], focused on the model-gated settings picker: the gate must make a
 * settings-only preset impossible to *create* through the UI while never rewriting a persisted
 * reference.
 */
@OptIn(ExperimentalTestApi::class)
class ModelPresetFormDialogTest {

    private fun model(id: Long, name: String) = LLMModel(
        id = id,
        name = name,
        providerId = 1L,
        active = true,
        displayName = null
    )

    private fun chatSettings(id: Long, modelId: Long, name: String): ChatModelSettings =
        ChatModelSettings(id = id, modelId = modelId, name = name)

    private val models = listOf(model(1L, "gpt-4o"), model(2L, "claude"))
    private val settings: List<ModelSettings> = listOf(
        chatSettings(20L, 1L, "Default"),
        chatSettings(21L, 1L, "Creative"),
        chatSettings(30L, 2L, "Claude default")
    )

    /**
     * Renders the preset form over a controlled draft and runs [block] against it.
     *
     * @param initialDraft The draft the form starts with.
     * @param block Assertions/interactions executed against the composed dialog and the live draft.
     */
    private fun setForm(
        initialDraft: ModelPresetFormState,
        block: ComposeUiTest.(draft: () -> ModelPresetFormState) -> Unit
    ) = runComposeUiTest {
        val state = mutableStateOf(initialDraft)
        setContent {
            ModelPresetFormDialog(
                title = "Edit Model Preset",
                formState = state.value,
                models = models,
                settings = settings,
                onFormUpdate = { update -> state.value = update(state.value) },
                onSave = { },
                onCancel = { }
            )
        }
        block { state.value }
    }

    @Test
    fun `settings picker is gated and its hint is shown while no model is selected`() {
        setForm(createEmptyModelPresetForm()) { draft ->
            onNodeWithText("Select a model first — a settings profile belongs to one model.").assertIsDisplayed()

            // The disabled picker must not open: its options stay unreachable.
            onNodeWithText("No settings profile").assertDoesNotExist()
            onNodeWithText("Default (CHAT)").assertDoesNotExist()
            // The draft is untouched by merely rendering the form.
            assertEquals(null, draft().modelSettingsId)
        }
    }

    @Test
    fun `selecting a model clears a settings selection that belongs to another model`() {
        // Claude is selected while the draft still holds gpt-4o's profile: the gate must drop it,
        // because the picker cannot offer a foreign profile.
        val draft = createEmptyModelPresetForm().copy(
            name = "preset",
            modelId = 1L,
            modelSettingsId = 20L
        )

        setForm(draft) { liveDraft ->
            onNodeWithText("gpt-4o").performClick()
            onNodeWithText("claude").performClick()

            assertEquals(2L, liveDraft().modelId)
            assertEquals(null, liveDraft().modelSettingsId)
        }
    }

    @Test
    fun `re-selecting the current model keeps a persisted settings reference`() {
        // The degenerate model-less-with-settings state is reachable through REST/preset tools: the
        // form displays it and a "No model" re-selection must round-trip it instead of clearing it.
        val draft = createEmptyModelPresetForm().copy(
            name = "legacy",
            modelId = null,
            modelSettingsId = 20L
        )

        setForm(draft) { liveDraft ->
            // The persisted reference is visible even though the picker is disabled.
            onNodeWithText("Default (CHAT)").assertIsDisplayed()

            // Re-picking the already-selected "No model" is a no-op for the settings reference.
            onNodeWithText("Model").performClick()
            onNodeWithText("No model").performClick()

            assertEquals(null, liveDraft().modelId)
            assertEquals(20L, liveDraft().modelSettingsId)
        }
    }

    @Test
    fun `clearing the model clears the settings selection`() {
        val draft = createEmptyModelPresetForm().copy(
            name = "preset",
            modelId = 1L,
            modelSettingsId = 20L
        )

        setForm(draft) { liveDraft ->
            onNodeWithText("gpt-4o").performClick()
            onNodeWithText("No model").performClick()

            assertEquals(null, liveDraft().modelId)
            assertEquals(null, liveDraft().modelSettingsId)
        }
    }

    @Test
    fun `settings picker offers only the selected model's profiles once a model is chosen`() {
        val draft = createEmptyModelPresetForm().copy(name = "preset", modelId = 1L)

        setForm(draft) { _ ->
            onNodeWithText("Settings Profile").performClick()

            onNodeWithText("Default (CHAT)").assertIsDisplayed()
            onNodeWithText("Creative (CHAT)").assertIsDisplayed()
            // The other model's profile is not attachable, so it is not offered.
            onNodeWithText("Claude default (CHAT)").assertDoesNotExist()
        }
    }
}
