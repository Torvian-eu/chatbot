package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.app.viewmodel.SettingsConfigViewModel
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import org.koin.compose.viewmodel.koinViewModel

/**
 * A stateful wrapper Composable for the Settings screen UI.
 *
 * This screen provides a tabbed interface for managing:
 * - LLM Providers (E4.S8-S12, E5.S4)
 * - LLM Models (E4.S1-S4)
 * - Model Settings Profiles (E4.S5-S6)
 *
 * It obtains its own ViewModels and manages the overall layout and navigation
 * between the different configuration sections.
 */
@Composable
fun SettingsScreen(
    providerConfigViewModel: ProviderConfigViewModel = koinViewModel(),
    modelConfigViewModel: ModelConfigViewModel = koinViewModel(),
    settingsConfigViewModel: SettingsConfigViewModel = koinViewModel()
) {
    // Tab state management
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Providers", "Models", "Settings")

    // Load initial data for all ViewModels
    LaunchedEffect(Unit) {
        providerConfigViewModel.loadProviders()
        modelConfigViewModel.loadModelsAndProviders()
        settingsConfigViewModel.loadModels()
    }

    // Collect states from ViewModels
    val providersState by providerConfigViewModel.providersState.collectAsState()
    val selectedProvider by providerConfigViewModel.selectedProvider.collectAsState()
    val providersDialogState by providerConfigViewModel.dialogState.collectAsState()

    val modelConfigState by modelConfigViewModel.modelConfigState.collectAsState()
    val selectedModel by modelConfigViewModel.selectedModel.collectAsState()
    val dialogState by modelConfigViewModel.dialogState.collectAsState()

    val selectedModelId by settingsConfigViewModel.selectedModelId.collectAsState()
    val settingsConfigState by settingsConfigViewModel.settingsConfigState.collectAsState()
    val settingsDialogState by settingsConfigViewModel.dialogState.collectAsState()

    // Create state objects
    val providersTabState = ProvidersTabState(
        providersUiState = providersState,
        selectedProvider = selectedProvider,
        dialogState = providersDialogState
    )

    val modelsTabState = ModelsTabState(
        modelConfigUiState = modelConfigState,
        selectedModel = selectedModel,
        dialogState = dialogState
    )

    val settingsConfigTabState = SettingsConfigTabState(
        settingsConfigState = settingsConfigState,
        selectedModelId = selectedModelId,
        dialogState = settingsDialogState
    )

    // Create action implementations
    val providersTabActions = object : ProvidersTabActions {
        override fun onLoadProviders() = providerConfigViewModel.loadProviders()
        override fun onSelectProvider(provider: LLMProvider?) = providerConfigViewModel.selectProvider(provider)
        override fun onStartAddingNewProvider() = providerConfigViewModel.startAddingNewProvider()
        override fun onCancelDialog() = providerConfigViewModel.cancelDialog()
        override fun onSaveProvider() = providerConfigViewModel.saveProvider()
        override fun onStartEditingProvider(provider: LLMProvider) =
            providerConfigViewModel.startEditingProvider(provider)

        override fun onStartDeletingProvider(provider: LLMProvider) =
            providerConfigViewModel.startDeletingProvider(provider)

        override fun onDeleteProvider(providerId: Long) = providerConfigViewModel.deleteProvider(providerId)
        override fun onUpdateProviderCredential() = providerConfigViewModel.updateProviderCredential()
        override fun onUpdateProviderForm(update: (ProviderFormState) -> ProviderFormState) =
            providerConfigViewModel.updateProviderForm(update)
    }

    val modelsTabActions = object : ModelsTabActions {
        override fun onLoadModelsAndProviders() = modelConfigViewModel.loadModelsAndProviders()
        override fun onStartAddingNewModel() = modelConfigViewModel.startAddingNewModel()
        override fun onSaveModel() = modelConfigViewModel.saveModel()
        override fun onStartEditingModel(model: LLMModel) = modelConfigViewModel.startEditingModel(model)
        override fun onStartDeletingModel(model: LLMModel) = modelConfigViewModel.startDeletingModel(model)
        override fun onDeleteModel(modelId: Long) = modelConfigViewModel.deleteModel(modelId)
        override fun onSelectModel(model: LLMModel?) = modelConfigViewModel.selectModel(model)
        override fun onUpdateModelForm(update: (ModelFormState) -> ModelFormState) =
            modelConfigViewModel.updateModelForm(update)

        override fun onCancelDialog() = modelConfigViewModel.cancelDialog()
    }

    val settingsConfigTabActions = object : SettingsConfigTabActions {
        override fun onLoadModels() = settingsConfigViewModel.loadModels()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab navigation
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                )
            }
        }

        // Tab content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (selectedTabIndex) {
                0 -> ProvidersTab(
                    state = providersTabState,
                    actions = providersTabActions
                )

                1 -> ModelsTab(
                    state = modelsTabState,
                    actions = modelsTabActions
                )

                2 -> SettingsConfigTab(
                    state = settingsConfigTabState,
                    actions = settingsConfigTabActions
                )
            }
        }
    }
}
