package eu.torvian.chatbot.app.compose.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Tests for [ModelPresetListPage]: rows render the resolved model/settings labels, incomplete presets
 * are marked (not hidden), and the add action fires.
 */
@OptIn(ExperimentalTestApi::class)
class ModelPresetListPageTest {

    private fun preset(
        id: Long,
        name: String,
        displayName: String? = null,
        modelId: Long? = 10L,
        modelSettingsId: Long? = 20L
    ) = ModelPresetDto(
        id = id,
        name = name,
        displayName = displayName,
        description = "",
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        createdAt = Instant.fromEpochSeconds(id),
        updatedAt = Instant.fromEpochSeconds(id)
    )

    private val modelsById: Map<Long, LLMModel> = mapOf(
        10L to LLMModel(id = 10L, name = "gpt-4o", providerId = 1L, active = true, displayName = "GPT-4o")
    )
    private val settingsById: Map<Long, ModelSettings> = mapOf(
        20L to ChatModelSettings(id = 20L, modelId = 10L, name = "Default")
    )

    /**
     * Renders [ModelPresetListPage] inside a compose test and runs [block] against it.
     *
     * @param presets Presets to render, in the order the repository would provide (name-ascending).
     * @param block Assertions/interactions executed against the composed page.
     */
    private fun setListPage(
        presets: List<ModelPresetDto>,
        block: ComposeUiTest.() -> Unit
    ) = runComposeUiTest {
        setContent {
            ModelPresetListPage(
                presets = presets,
                modelsById = modelsById,
                settingsById = settingsById,
                onPresetSelected = {},
                onAddNewPreset = {}
            )
        }
        block()
    }

    @Test
    fun rows_renderThePresetNamesAndTheirResolvedConfiguration() {
        setListPage(
            listOf(
                preset(1, "alpha", displayName = "Alpha"),
                preset(2, "beta")
            )
        ) {
            onNodeWithText("Alpha").assertIsDisplayed()
            onNodeWithText("alpha").assertIsDisplayed()
            onNodeWithText("beta").assertIsDisplayed()
            // Both rows render the same resolved labels (one node each), in the repository's order.
            onAllNodesWithText("Model: GPT-4o • Settings: Default (CHAT)").assertCountEquals(2)
            // Both presets are complete, so neither is marked.
            onAllNodesWithText("Incomplete").assertCountEquals(0)
        }
    }

    @Test
    fun incompletePreset_isMarkedInsteadOfHidden() {
        setListPage(
            listOf(
                preset(1, "alpha"),
                preset(2, "orphan", modelId = null, modelSettingsId = null)
            )
        ) {
            // The row stays visible (it is still attachable) and reports the missing references.
            onNodeWithText("orphan").assertIsDisplayed()
            onNodeWithText("Model: No model • Settings: No settings").assertIsDisplayed()
            onAllNodesWithText("Incomplete").assertCountEquals(1)
        }
    }

    @Test
    fun addAction_fires() {
        var addClicks = 0
        runComposeUiTest {
            setContent {
                ModelPresetListPage(
                    presets = listOf(preset(1, "alpha")),
                    modelsById = modelsById,
                    settingsById = settingsById,
                    onPresetSelected = {},
                    onAddNewPreset = { addClicks++ }
                )
            }

            onNodeWithText("Add preset").performClick()

            assertEquals(1, addClicks)
        }
    }

    @Test
    fun emptyList_showsTheEmptyStateCopy() {
        setListPage(emptyList()) {
            onNodeWithText("No model presets configured yet.").assertIsDisplayed()
            onNodeWithText("1 preset(s) • select a preset to view or edit its model and settings profile.")
                .assertDoesNotExist()
        }
    }
}
