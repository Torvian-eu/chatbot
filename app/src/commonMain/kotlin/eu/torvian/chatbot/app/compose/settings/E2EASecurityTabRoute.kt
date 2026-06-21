package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.viewmodel.settings.E2EASecurityViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the E2EA Security settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together.
 * E2EA Security is a read-only settings category that displays the client's
 * cryptographic identity (signer ID and public key) for worker authorization.
 *
 * @param viewModel E2EA Security ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category
 *   in the sidebar; triggers a refresh of the identity information.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 *   current E2EA Security page in the breadcrumb trail.
 */
@Composable
fun E2EASecurityTabRoute(
    viewModel: E2EASecurityViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Update breadcrumbs
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.E2EASecurity.displayLabel))
    }

    // Refresh identity info when the category is re-selected
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.loadIdentityInfo()
        }
    }

    // Collect reactive state
    val signerId by viewModel.signerId.collectAsState()
    val publicKeyBase64 by viewModel.publicKeyBase64.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Call the presentational E2EASecurityTab, wiring ViewModel actions to UI callbacks
    E2EASecurityTab(
        signerId = signerId,
        publicKeyBase64 = publicKeyBase64,
        isLoading = isLoading,
        error = error,
        onCopySignerId = { viewModel.copySignerId() },
        onCopyPublicKey = { viewModel.copyPublicKey() },
        onCopyRegisterCommand = { viewModel.copyRegisterCommand() },
        modifier = modifier
    )
}
