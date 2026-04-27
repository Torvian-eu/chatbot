package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
 * Route composable for the Model Settings category.
 *
 * The route keeps the ViewModel wiring, the selected settings profile page id,
 * and the breadcrumb updates together so the visible page stays separate from
 * the underlying settings-data selection.
 *
 * @param authState Authentication context for permission-sensitive settings actions.
 * @param viewModel Settings ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 * current Model Settings page in the breadcrumb trail.
 */
@Composable
fun ModelSettingsConfigTabRoute(
    authState: AuthState.Authenticated,
    viewModel: ModelSettingsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    var selectedSettingsDetailId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Tab-local initial load.
    LaunchedEffect(Unit) {
        viewModel.loadModelsAndSettings()
    }

    // Collect tab state here.
    val modelsForSettings by viewModel.modelsState.collectAsState()
    val settingsListForSelectedModel by viewModel.settingsListForSelectedModel.collectAsState()
    val selectedModelForSettings by viewModel.selectedModel.collectAsState()
    val selectedSettings by viewModel.selectedSettings.collectAsState()
    val settingsDialogState by viewModel.dialogState.collectAsState()

    // If the opened profile disappears from the current model context, fall back to the list page.
    LaunchedEffect(settingsListForSelectedModel, selectedSettingsDetailId) {
        val activeSettingsId = selectedSettingsDetailId
        val currentSettingsList = settingsListForSelectedModel

        if (activeSettingsId != null && currentSettingsList != null && currentSettingsList.none { it.settings.id == activeSettingsId }) {
            selectedSettingsDetailId = null
            viewModel.selectSettings(null)
        }
    }

    val activeModelForBreadcrumbs = selectedModelForSettings
    val activeSettingsForBreadcrumbs = selectedSettings
    val breadcrumbs = if (selectedSettingsDetailId != null && activeModelForBreadcrumbs != null && activeSettingsForBreadcrumbs != null) {
        listOf(
            "Settings",
            SettingsCategory.ModelSettings.displayLabel,
            activeModelForBreadcrumbs.displayName?.takeIf { it.isNotBlank() } ?: activeModelForBreadcrumbs.name,
            activeSettingsForBreadcrumbs.settings.name
        )
    } else {
        listOf("Settings", SettingsCategory.ModelSettings.displayLabel)
    }

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    // Build presentational state
    val state = ModelSettingsConfigTabState(
        modelsUiState = modelsForSettings,
        settingsListForSelectedModel = settingsListForSelectedModel,
        selectedModel = selectedModelForSettings,
        selectedSettings = selectedSettings,
        selectedSettingsDetailId = selectedSettingsDetailId,
        dialogState = settingsDialogState
    )

    // Build actions forwarding to VM
    val actions = object : ModelSettingsConfigTabActions {
        override fun onLoadModelsAndSettings() = viewModel.loadModelsAndSettings()
        override fun onSelectModel(model: LLMModel?) = viewModel.selectModel(model)
        override fun onSelectSettings(settingsDetails: ModelSettingsDetails?) {
            selectedSettingsDetailId = settingsDetails?.settings?.id
            viewModel.selectSettings(settingsDetails)
        }

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
