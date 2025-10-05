package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Providers tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun ProvidersTabRoute(
    viewModel: ProviderConfigViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadProviders()
    }

    // Collect tab state here
    val providersState by viewModel.providersState.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()

    // Build presentational state
    val state = ProvidersTabState(
        providersUiState = providersState,
        selectedProvider = selectedProvider,
        dialogState = dialogState
    )

    // Build actions forwarding to VM
    val actions = object : ProvidersTabActions {
        override fun onLoadProviders() = viewModel.loadProviders()
        override fun onSelectProvider(provider: LLMProvider?) = viewModel.selectProvider(provider)
        override fun onStartAddingNewProvider() = viewModel.startAddingNewProvider()
        override fun onCancelDialog() = viewModel.cancelDialog()
        override fun onSaveProvider() = viewModel.saveProvider()
        override fun onStartEditingProvider(provider: LLMProvider) = viewModel.startEditingProvider(provider)
        override fun onStartDeletingProvider(provider: LLMProvider) = viewModel.startDeletingProvider(provider)
        override fun onDeleteProvider(providerId: Long) = viewModel.deleteProvider(providerId)
        override fun onUpdateProviderCredential() = viewModel.updateProviderCredential()
        override fun onUpdateProviderForm(update: (ProviderFormState) -> ProviderFormState) =
            viewModel.updateProviderForm(update)
    }

    // Call the presentational ProvidersTab
    ProvidersTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
