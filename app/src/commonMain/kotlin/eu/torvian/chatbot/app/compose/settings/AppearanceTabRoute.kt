package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.PreferencesViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Appearance settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together.
 *
 * @param authState Authentication context.
 * @param viewModel Preferences ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category
 *   in the sidebar; triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 *   current Appearance page in the breadcrumb trail.
 */
@Composable
fun AppearanceTabRoute(
    authState: AuthState.Authenticated,
    viewModel: PreferencesViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Update breadcrumbs
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.Appearance.displayLabel))
    }

    // Sync detailed preferences when the tab is displayed
    LaunchedEffect(Unit) {
        viewModel.syncDetailedPreferences()
    }

    // Collect tab state
    val isDeviceScoped by viewModel.isDeviceScoped.collectAsState()
    val detailedPreferences by viewModel.detailedPreferences.collectAsState()

    // Build presentational state
    val state = AppearanceTabState(
        isDeviceScoped = isDeviceScoped,
        detailedPreferences = detailedPreferences
    )

    // Build actions forwarding to VM
    val actions = object : AppearanceTabActions {
        override fun onSetTheme(theme: String?) = viewModel.setTheme(theme)
        override fun onSetDeviceScoped(isDeviceScoped: Boolean) {
            viewModel.setDeviceScoped(isDeviceScoped)
        }
    }

    // Call the presentational AppearanceTab
    AppearanceTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
