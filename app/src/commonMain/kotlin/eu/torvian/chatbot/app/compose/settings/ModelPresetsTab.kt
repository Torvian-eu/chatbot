package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ModelPresetDialogState
import eu.torvian.chatbot.app.domain.contracts.ModelPresetFormState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * State contract for the Model Presets tab.
 *
 * @property presetsUiState Load state of the user's preset list (name-ascending).
 * @property selectedPreset The preset open in the master-detail view, or null on the list page.
 * @property dialogState The active dialog (add/edit/delete form or confirmation).
 * @property models All accessible models for the form's model picker.
 * @property settings All accessible settings profiles; the form gates them per selected model.
 * @property modelsById Model lookup map for resolving a preset's model reference.
 * @property settingsById Settings lookup map for resolving a preset's settings reference.
 */
data class ModelPresetsTabState(
    val presetsUiState: DataState<RepositoryError, List<ModelPresetDto>>,
    val selectedPreset: ModelPresetDto?,
    val dialogState: ModelPresetDialogState,
    val models: List<LLMModel> = emptyList(),
    val settings: List<ModelSettings> = emptyList(),
    val modelsById: Map<Long, LLMModel> = emptyMap(),
    val settingsById: Map<Long, ModelSettings> = emptyMap()
)

/**
 * Action callbacks for the Model Presets tab.
 */
interface ModelPresetsTabActions {
    /** Reloads presets and the model/settings catalogs in parallel. */
    fun onLoadPresetsAndCatalogs()

    /** Selects a preset for the master-detail view, or clears selection when null. */
    fun onSelectPreset(preset: ModelPresetDto?)

    /** Opens the add-preset form dialog. */
    fun onStartAddingNewPreset()

    /** Opens the edit-preset form dialog for [preset]. */
    fun onStartEditingPreset(preset: ModelPresetDto)

    /** Opens the delete-preset confirmation dialog for [preset]. */
    fun onStartDeletingPreset(preset: ModelPresetDto)

    /** Applies an update function to the active form draft. */
    fun onUpdatePresetForm(update: (ModelPresetFormState) -> ModelPresetFormState)

    /** Saves the active form draft (create or update). */
    fun onSavePreset()

    /** Deletes a preset by id. */
    fun onDeletePreset(presetId: Long)

    /** Cancels any dialog (form or confirmation). */
    fun onCancelDialog()
}

/**
 * Model Presets management tab with separate list and detail pages.
 *
 * The tab stays presentational: it switches between list/detail while the route owns page
 * navigation state and the ViewModel owns dialogs and form state.
 *
 * @param state Current Model Presets tab state from the route.
 * @param actions ViewModel-forwarding actions for preset CRUD flows.
 * @param onOpenPresetDetails Callback invoked when the user opens a preset detail page.
 * @param onBackToPresetList Callback invoked when the user returns to the preset list.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun ModelPresetsTab(
    state: ModelPresetsTabState,
    actions: ModelPresetsTabActions,
    onOpenPresetDetails: (ModelPresetDto) -> Unit,
    onBackToPresetList: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.presetsUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading model presets...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load model presets",
                    error = uiState.error,
                    onRetry = { actions.onLoadPresetsAndCatalogs() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val selectedPreset = state.selectedPreset

                if (selectedPreset != null) {
                    ModelPresetDetailPage(
                        preset = selectedPreset,
                        modelsById = state.modelsById,
                        settingsById = state.settingsById,
                        onBackToList = onBackToPresetList,
                        onEdit = { actions.onStartEditingPreset(it) },
                        onDelete = { actions.onStartDeletingPreset(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ModelPresetListPage(
                        presets = uiState.data,
                        modelsById = state.modelsById,
                        settingsById = state.settingsById,
                        onPresetSelected = { preset -> onOpenPresetDetails(preset) },
                        onAddNewPreset = { actions.onStartAddingNewPreset() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Model presets will appear here.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadPresetsAndCatalogs() }) {
                            Text("Load Model Presets")
                        }
                    }
                }
            }
        }
    }

    ModelPresetsDialogs(
        dialogState = state.dialogState,
        actions = actions,
        models = state.models,
        settings = state.settings
    )
}
