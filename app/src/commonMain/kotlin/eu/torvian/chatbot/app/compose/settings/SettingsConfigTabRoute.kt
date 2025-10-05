package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.SettingsFormState
import eu.torvian.chatbot.app.viewmodel.SettingsConfigViewModel
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Settings Config tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun SettingsConfigTabRoute(
    viewModel: SettingsConfigViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadModelsAndSettings()
    }

    // Collect tab state here
    val modelsForSettings by viewModel.modelsState.collectAsState()
    val settingsListForSelectedModel by viewModel.settingsListForSelectedModel.collectAsState()
    val selectedModelForSettings by viewModel.selectedModel.collectAsState()
    val selectedSettings by viewModel.selectedSettings.collectAsState()
    val settingsDialogState by viewModel.dialogState.collectAsState()

    // Build presentational state
    val state = SettingsConfigTabState(
        modelsUiState = modelsForSettings,
        settingsListForSelectedModel = settingsListForSelectedModel,
        selectedModel = selectedModelForSettings,
        selectedSettings = selectedSettings,
        dialogState = settingsDialogState
    )

    // Build actions forwarding to VM
    val actions = object : SettingsConfigTabActions {
        override fun onLoadModelsAndSettings() = viewModel.loadModelsAndSettings()
        override fun onSelectModel(model: LLMModel?) = viewModel.selectModel(model)
        override fun onSelectSettings(settings: ModelSettings?) = viewModel.selectSettings(settings)
        override fun onStartAddingNewSettings() = viewModel.startAddingNewSettings()
        override fun onStartEditingSettings(settings: ModelSettings) = viewModel.startEditingSettings(settings)
        override fun onStartDeletingSettings(settings: ModelSettings) = viewModel.startDeletingSettings(settings)
        override fun onUpdateSettingsForm(update: (SettingsFormState) -> SettingsFormState) = viewModel.updateSettingsForm(update)
        override fun onSaveSettings() = viewModel.saveSettings()
        override fun onDeleteSettings(settingsId: Long) = viewModel.deleteSettings(settingsId)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    // Call the presentational SettingsConfigTab
    SettingsConfigTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
