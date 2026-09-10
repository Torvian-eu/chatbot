package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.ModelPresetFormState
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.ModelPresetsViewModel
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Model Presets settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together so the visible page stays
 * separate from the underlying preset-data selection. Selection state is owned by the
 * [ModelPresetsViewModel]; this route only observes it to decide between the list and detail pages.
 *
 * @param authState Authentication context (currently unused by the presets tab; presets are
 *   ownership-based per user).
 * @param modifier Modifier applied to the presentational tab.
 * @param viewModel Model Presets ViewModel resolved from Koin.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar;
 *   triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current Model
 *   Presets page in the breadcrumb trail.
 */
@Composable
fun ModelPresetsTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: ModelPresetsViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load of presets plus the model/settings catalogs the form and detail page need.
    LaunchedEffect(Unit) {
        viewModel.loadPresetsAndCatalogs()
    }

    // Reset to list view when the category is re-selected in the sidebar.
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.selectPreset(null)
        }
    }

    val presetsState by viewModel.presetsState.collectAsState()
    val selectedPreset by viewModel.selectedPreset.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val modelsState by viewModel.modelsState.collectAsState()
    val settingsState by viewModel.settingsState.collectAsState()
    val modelsById by viewModel.modelsById.collectAsState()
    val settingsById by viewModel.settingsById.collectAsState()

    // If a preset disappears while its detail page is open (e.g. deleted in another client), fall
    // back to the list page.
    val presets = presetsState.dataOrNull
    val selectedPresetForFallback = selectedPreset
    LaunchedEffect(presets, selectedPresetForFallback) {
        if (presets != null && selectedPresetForFallback != null && presets.none { it.id == selectedPresetForFallback.id }) {
            viewModel.selectPreset(null)
        }
    }

    val breadcrumbs = selectedPreset?.let {
        listOf(
            "Settings",
            SettingsCategory.ModelPresets.displayLabel,
            it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.name
        )
    } ?: listOf("Settings", SettingsCategory.ModelPresets.displayLabel)

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    val state = ModelPresetsTabState(
        presetsUiState = presetsState,
        selectedPreset = selectedPreset,
        dialogState = dialogState,
        models = modelsState.dataOrNull.orEmpty(),
        settings = settingsState.dataOrNull.orEmpty(),
        modelsById = modelsById,
        settingsById = settingsById
    )

    val actions = object : ModelPresetsTabActions {
        override fun onLoadPresetsAndCatalogs() = viewModel.loadPresetsAndCatalogs()
        override fun onSelectPreset(preset: ModelPresetDto?) = viewModel.selectPreset(preset)
        override fun onStartAddingNewPreset() = viewModel.startAddingNewPreset()
        override fun onStartEditingPreset(preset: ModelPresetDto) = viewModel.startEditingPreset(preset)
        override fun onStartDeletingPreset(preset: ModelPresetDto) = viewModel.startDeletingPreset(preset)
        override fun onUpdatePresetForm(update: (ModelPresetFormState) -> ModelPresetFormState) =
            viewModel.updatePresetForm(update)
        override fun onSavePreset() = viewModel.savePreset()
        override fun onDeletePreset(presetId: Long) = viewModel.deletePreset(presetId)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    ModelPresetsTab(
        state = state,
        actions = actions,
        onOpenPresetDetails = { preset -> viewModel.selectPreset(preset) },
        onBackToPresetList = { viewModel.selectPreset(null) },
        modifier = modifier
    )
}
