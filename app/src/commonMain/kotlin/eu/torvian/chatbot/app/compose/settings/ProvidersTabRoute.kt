package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider
import org.koin.compose.viewmodel.koinViewModel
import eu.torvian.chatbot.app.repository.AuthState

/**
 * Route composable for the Providers tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun ProvidersTabRoute(
    authState: AuthState.Authenticated,
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
        override fun onSelectProvider(providerDetails: LLMProviderDetails?) = viewModel.selectProvider(providerDetails)
        override fun onStartAddingNewProvider() = viewModel.startAddingNewProvider()
        override fun onCancelDialog() = viewModel.cancelDialog()
        override fun onSaveProvider() = viewModel.saveProvider()
        override fun onStartEditingProvider(provider: LLMProvider) = viewModel.startEditingProvider(provider)
        override fun onStartDeletingProvider(provider: LLMProvider) = viewModel.startDeletingProvider(provider)
        override fun onDeleteProvider(providerId: Long) = viewModel.deleteProvider(providerId)
        override fun onUpdateProviderCredential() = viewModel.updateProviderCredential()
        override fun onUpdateProviderForm(update: (ProviderFormState) -> ProviderFormState) =
            viewModel.updateProviderForm(update)

        // Access management actions
        override fun onMakeProviderPublic(providerDetails: LLMProviderDetails) =
            viewModel.makeProviderPublic(providerDetails)
        override fun onMakeProviderPrivate(providerDetails: LLMProviderDetails) =
            viewModel.makeProviderPrivate(providerDetails)
        override fun onOpenManageAccessDialog(providerDetails: LLMProviderDetails) =
            viewModel.openManageAccessDialog(providerDetails)
        override fun onOpenGrantAccessDialog() =
            viewModel.openGrantAccessDialog()
        override fun onCloseGrantAccessDialog() =
            viewModel.closeGrantAccessDialog()
        override fun onUpdateGrantAccessForm(form: GrantAccessFormState) =
            viewModel.updateGrantAccessForm { form }
        override fun onGrantProviderAccess(providerId: Long, groupId: Long, accessMode: String) =
            viewModel.grantProviderAccess(providerId, groupId, accessMode)
        override fun onRevokeProviderAccess(providerId: Long, groupId: Long, accessMode: String) =
            viewModel.revokeProviderAccess(providerId, groupId, accessMode)
    }

    // Call the presentational ProvidersTab
    ProvidersTab(
        state = state,
        actions = actions,
        authState = authState,
        modifier = modifier
    )
}
