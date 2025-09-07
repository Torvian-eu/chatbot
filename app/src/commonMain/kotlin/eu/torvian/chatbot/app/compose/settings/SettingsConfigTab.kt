package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState

/**
 * Settings management tab with ViewModel integration.
 * Demonstrates proper state collection and error handling.
 * Full implementation will be completed in Phase 4.
 */
@Composable
fun SettingsConfigTab(
    state: SettingsConfigTabState,
    actions: SettingsConfigTabActions,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Model Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        when (val uiState = state.modelsUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading models and settings...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                val error = uiState.error
                ErrorStateDisplay(
                    title = "Failed to load models and settings",
                    error = error,
                    onRetry = { actions.onLoadModels() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val models = uiState.data
                val settingsListForSelectedModel = state.settingsListForSelectedModel

                if (models.isEmpty()) {
                    Text(
                        text = "No models available for settings configuration.\nFull UI will be implemented in Phase 4.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Selected Model ID: ${state.selectedModel?.id ?: "None"}\n" +
                                "Available models: ${models.size}\n" +
                                "Settings profiles for selected model: ${settingsListForSelectedModel?.size ?: "N/A"}\n\n" +
                                "Full settings management UI will be implemented in Phase 4.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Button(onClick = { actions.onLoadModels() }) {
                        Text("Load Models")
                    }
                }
            }
        }
    }
}
