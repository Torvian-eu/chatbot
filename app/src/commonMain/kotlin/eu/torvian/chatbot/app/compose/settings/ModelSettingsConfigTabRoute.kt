package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ModelSettingsFormState
import eu.torvian.chatbot.app.viewmodel.ModelSettingsViewModel
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import org.koin.compose.viewmodel.koinViewModel
import eu.torvian.chatbot.app.repository.AuthState

/**
 * Route composable for the Settings Config tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun ModelSettingsConfigTabRoute(
    authState: AuthState.Authenticated,
    viewModel: ModelSettingsViewModel = koinViewModel(),
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
    val state = ModelSettingsConfigTabState(
        modelsUiState = modelsForSettings,
        settingsListForSelectedModel = settingsListForSelectedModel,
        selectedModel = selectedModelForSettings,
        selectedSettings = selectedSettings,
        dialogState = settingsDialogState
    )

    // Build actions forwarding to VM
    val actions = object : ModelSettingsConfigTabActions {
        override fun onLoadModelsAndSettings() = viewModel.loadModelsAndSettings()
        override fun onSelectModel(model: LLMModel?) = viewModel.selectModel(model)
        override fun onSelectSettings(settingsDetails: ModelSettingsDetails?) =
            viewModel.selectSettings(settingsDetails)

        override fun onStartAddingNewSettings() = viewModel.startAddingNewSettings()
        override fun onStartEditingSettings(settings: ModelSettings) = viewModel.startEditingSettings(settings)
        override fun onStartDeletingSettings(settings: ModelSettings) = viewModel.startDeletingSettings(settings)
        override fun onUpdateSettingsForm(update: (ModelSettingsFormState) -> ModelSettingsFormState) =
            viewModel.updateSettingsForm(update)

        override fun onSaveSettings() = viewModel.saveSettings()
        override fun onDeleteSettings(settingsId: Long) = viewModel.deleteSettings(settingsId)
        override fun onCancelDialog() = viewModel.cancelDialog()

        // Access management actions
        override fun onMakeSettingsPublic(settingsDetails: ModelSettingsDetails) =
            viewModel.makeSettingsPublic(settingsDetails)

        override fun onMakeSettingsPrivate(settingsDetails: ModelSettingsDetails) =
            viewModel.makeSettingsPrivate(settingsDetails)

        override fun onOpenManageAccessDialog(settingsDetails: ModelSettingsDetails) =
            viewModel.openManageAccessDialog(settingsDetails)

        // Grant dialog helpers
        override fun onOpenGrantAccessDialog() = viewModel.openGrantAccessDialog()
        override fun onCloseGrantAccessDialog() = viewModel.closeGrantAccessDialog()
        override fun onUpdateGrantAccessForm(form: GrantAccessFormState) =
            viewModel.updateGrantAccessForm { form }

        override fun onGrantSettingsAccess(settingsId: Long, groupId: Long, accessMode: String) =
            viewModel.grantSettingsAccess(settingsId, groupId, accessMode)

        override fun onRevokeSettingsAccess(settingsId: Long, groupId: Long, accessMode: String) =
            viewModel.revokeSettingsAccess(settingsId, groupId, accessMode)
    }

    // Call the presentational ModelSettingsConfigTab
    ModelSettingsConfigTab(
        state = state,
        actions = actions,
        authState = authState,
        modifier = modifier
    )
}
