package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ProviderFormState
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails
import eu.torvian.chatbot.common.models.llm.LLMProvider
import org.koin.compose.viewmodel.koinViewModel
import eu.torvian.chatbot.app.repository.AuthState

/**
 * Route composable for the Providers settings category.
 *
 * The route keeps the ViewModel wiring, provider-local page state, and breadcrumb
 * updates together so the page switch stays separate from dialog state.
 *
 * Selection state is owned by the [ProviderConfigViewModel]; this route only
 * observes [ProviderConfigViewModel.selectedProvider] to decide whether to show
 * the list or detail page.
 *
 * @param authState Authentication context for permission-sensitive provider actions.
 * @param viewModel Provider configuration ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category
 *   in the sidebar; triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 *   current Providers page in the breadcrumb trail.
 */
@Composable
fun ProvidersTabRoute(
    authState: AuthState.Authenticated,
    viewModel: ProviderConfigViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadProviders()
    }

    // Reset to list view when the category is re-selected in the sidebar.
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.selectProvider(null)
        }
    }

    // Collect tab state here
    val providersState by viewModel.providersState.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val providers = (providersState as? DataState.Success)?.data

    // If a provider disappears while its detail page is open, fall back to the list page.
    LaunchedEffect(providers, selectedProvider) {
        val selectedProviderId = selectedProvider?.provider?.id
        if (providers != null && selectedProviderId != null && providers.none { it.provider.id == selectedProviderId }) {
            viewModel.selectProvider(null)
        }
    }

    val breadcrumbs = selectedProvider?.let {
        listOf(
            "Settings",
            SettingsCategory.Providers.displayLabel,
            it.provider.name
        )
    } ?: listOf("Settings", SettingsCategory.Providers.displayLabel)

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

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
        override fun onTestProviderConnectionInDialog() = viewModel.testProviderConnectionInDialog()
        override fun onListProviderModels(providerId: Long) = viewModel.listProviderModels(providerId)
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
        selectedProviderId = selectedProvider?.provider?.id,
        onOpenProviderDetails = { providerDetails ->
            viewModel.selectProvider(providerDetails)
        },
        onBackToProviderList = {
            viewModel.selectProvider(null)
        },
        modifier = modifier
    )
}
