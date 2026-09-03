package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.ConversationCompactionViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Conversation Compaction settings category.
 *
 * The route wires the [ConversationCompactionViewModel] to the presentational
 * [ConversationCompactionTab]: it loads the stored preference/models/settings on first composition
 * and forwards user actions to the ViewModel. The breadcrumb stays fixed for this single-page
 * category, mirroring the Appearance tab's route pattern.
 *
 * @param authState Authentication context (retained for signature consistency with sibling routes).
 * @param modifier Modifier applied to the presentational tab.
 * @param viewModel ViewModel resolved from Koin.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current page.
 */
@Composable
fun ConversationCompactionTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: ConversationCompactionViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.ConversationCompaction.displayLabel))
    }

    // Load once per category visit; re-selecting the category resets the draft to the stored
    // configuration (the reset signal re-runs the load).
    LaunchedEffect(categoryResetSignal) {
        viewModel.load()
    }

    val models by viewModel.models.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val compatibleSettings by viewModel.compatibleSettings.collectAsState()
    val selectedSettingsId by viewModel.selectedSettingsId.collectAsState()
    val storedPreference by viewModel.storedPreference.collectAsState()
    val draftEnabled by viewModel.draftEnabled.collectAsState()
    val instruction by viewModel.instruction.collectAsState()
    val systemMessage by viewModel.systemMessage.collectAsState()
    val summaryLabel by viewModel.summaryLabel.collectAsState()
    val thresholdText by viewModel.thresholdText.collectAsState()
    val validationErrors by viewModel.validationErrors.collectAsState()
    val saving by viewModel.saving.collectAsState()

    val selectedModel = models.dataOrNull?.firstOrNull { it.id == selectedModelId }
    val selectedSettings = compatibleSettings.firstOrNull { it.settings.id == selectedSettingsId }

    val state = ConversationCompactionTabState(
        models = models,
        selectedModel = selectedModel,
        compatibleSettings = compatibleSettings,
        selectedSettings = selectedSettings,
        storedPreference = storedPreference,
        enabled = draftEnabled,
        instruction = instruction,
        systemMessage = systemMessage,
        summaryLabel = summaryLabel,
        thresholdText = thresholdText,
        validationErrors = validationErrors,
        saving = saving
    )

    val actions = object : ConversationCompactionTabActions {
        override fun onLoad() = viewModel.load()
        override fun onSelectModel(modelId: Long?) = viewModel.selectModel(modelId)
        override fun onSelectSettings(settingsId: Long?) = viewModel.selectSettings(settingsId)
        override fun onToggleEnabled(enable: Boolean) = viewModel.setEnabled(enable)
        override fun onUpdateInstruction(text: String) = viewModel.updateInstruction(text)
        override fun onUpdateSystemMessage(text: String) = viewModel.updateSystemMessage(text)
        override fun onUpdateSummaryLabel(text: String) = viewModel.updateSummaryLabel(text)
        override fun onUpdateThreshold(text: String) = viewModel.updateThresholdText(text)
        override fun onSave() = viewModel.save()
        override fun onClear() = viewModel.clear()
    }

    ConversationCompactionTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}