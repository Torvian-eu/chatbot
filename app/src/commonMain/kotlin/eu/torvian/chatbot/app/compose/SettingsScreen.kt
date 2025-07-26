package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.app.viewmodel.ProviderConfigViewModel
import eu.torvian.chatbot.app.viewmodel.SettingsConfigViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * A stateful wrapper Composable for the Settings screen UI.
 * It is responsible for obtaining its own ViewModels and will pass relevant states
 * and callbacks down to stateless content composables (to be implemented in PR 24).
 * (PR 24: Implement Settings Screen UI)
 */
@Composable
fun SettingsScreen(
    providerConfigViewModel: ProviderConfigViewModel = koinViewModel(),
    modelConfigViewModel: ModelConfigViewModel = koinViewModel(),
    settingsConfigViewModel: SettingsConfigViewModel = koinViewModel()
) {
    // Collect some states to show they are available
    val providerState by providerConfigViewModel.providersState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Settings Screen\n(To be implemented in PR 24)\n" +
            "Provider VM state: ${providerState::class.simpleName}",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}