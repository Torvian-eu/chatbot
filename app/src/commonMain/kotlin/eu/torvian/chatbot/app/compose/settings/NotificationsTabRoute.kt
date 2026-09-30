package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.NotificationsViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Notifications settings category.
 *
 * Wires the [NotificationsViewModel] to the presentational [NotificationsTab], refreshing the
 * platform permission state on first composition, whenever the user re-selects the category in the
 * sidebar (the reset signal re-runs the read), and whenever the app regains the user's attention —
 * which is when a system prompt answered outside the app, or a change made in the OS or browser
 * settings, becomes visible. The attention read is a lifecycle callback, so nothing polls or ticks.
 *
 * @param authState Authentication context (retained for signature consistency with sibling routes).
 * @param modifier Modifier applied to the presentational tab.
 * @param viewModel ViewModel resolved from Koin.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current page.
 */
@Composable
fun NotificationsTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: NotificationsViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    LaunchedEffect(Unit) {
        onBreadcrumbsChanged(listOf("Settings", SettingsCategory.Notifications.displayLabel))
    }

    // Re-selecting the category re-reads the permission, so a change made while this screen was
    // closed is picked up.
    LaunchedEffect(categoryResetSignal) {
        viewModel.loadPermissionState()
    }

    // A prompt's answer and a settings change both land while the app is not in front, so coming
    // back to it is the moment the surface can converge without the user leaving the category.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.loadPermissionState()
    }

    val preference by viewModel.preference.collectAsState()
    val osChannel by viewModel.osChannel.collectAsState()
    val saving by viewModel.saving.collectAsState()

    val state = NotificationsTabState(
        preference = preference,
        osChannel = osChannel,
        saving = saving
    )

    val actions = object : NotificationsTabActions {
        override fun onSetEnabled(enabled: Boolean) = viewModel.setEnabled(enabled)
        override fun onSetSoundEnabled(enabled: Boolean) = viewModel.setSoundEnabled(enabled)
        override fun onSetOsNotificationEnabled(enabled: Boolean) = viewModel.setOsNotificationEnabled(enabled)
        override fun onRequestPermission() = viewModel.requestPermission()
    }

    NotificationsTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
