package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.models.api.access.LLMProviderDetails

/**
 * Full-width page for browsing configured providers.
 *
 * The page now owns the shared shell, header copy, and add action while the list
 * panel focuses on row rendering and empty-state behavior.
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
    SettingsListPageTemplate(
        title = "Providers",
        subtitle = if (providers.isEmpty()) {
            "No providers configured yet. Use the add action to create your first provider."
        } else {
            "${providers.size} configured • select a provider to open its details."
        },
        modifier = modifier,
        actions = {
            RequiresAnyPermission(
                authState = authState,
                permissions = listOf(
                    CommonPermissions.CREATE_LLM_PROVIDER,
                    CommonPermissions.MANAGE_LLM_PROVIDERS
                )
            ) {
                FilledTonalButton(onClick = onAddNewProvider) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add provider", maxLines = 1, softWrap = false)
                }
            }
        }
    ) {
        ProvidersListPanel(
            providers = providers,
            selectedProvider = selectedProvider,
            onProviderSelected = onProviderSelected,
            modifier = Modifier.fillMaxSize()
        )
    }
}



