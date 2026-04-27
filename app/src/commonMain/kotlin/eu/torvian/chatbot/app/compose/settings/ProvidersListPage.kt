package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails

/**
 * Full-width page for browsing configured providers.
 *
 * The page intentionally delegates the actual list rendering to [ProvidersListPanel]
 * so the existing row item styling and permission-gated add action remain stable.
 *
 * @param providers Providers to render in the list.
 * @param selectedProvider Currently focused provider, used only for row highlighting.
 * @param onProviderSelected Callback invoked when the user opens a provider detail page.
 * @param onAddNewProvider Callback invoked when the user starts the add-provider flow.
 * @param authState Authentication state used to gate provider-creation actions.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ProvidersListPage(
    providers: List<LLMProviderDetails>,
    selectedProvider: LLMProviderDetails?,
    onProviderSelected: (LLMProviderDetails) -> Unit,
    onAddNewProvider: () -> Unit,
    authState: AuthState,
    modifier: Modifier = Modifier
) {
    ProvidersListPanel(
        providers = providers,
        selectedProvider = selectedProvider,
        onProviderSelected = onProviderSelected,
        onAddNewProvider = onAddNewProvider,
        authState = authState,
        modifier = modifier.fillMaxSize()
    )
}



