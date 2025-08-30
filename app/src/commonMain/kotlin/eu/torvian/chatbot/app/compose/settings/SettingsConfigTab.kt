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

        when {
            state.modelsForSelection.isLoading -> {
                LoadingStateDisplay(
                    message = "Loading models for settings...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            state.modelsForSelection.isError -> {
                val error = state.modelsForSelection.errorOrNull
                if (error != null) {
                    ErrorStateDisplay(
                        title = "Failed to load models",
                        error = error,
                        onRetry = { actions.onLoadModels() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Unknown error occurred",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            state.modelsForSelection.isSuccess -> {
                val models = state.modelsForSelection.dataOrNull ?: emptyList()

                if (models.isEmpty()) {
                    Text(
                        text = "No models available for settings configuration.\nFull UI will be implemented in Phase 4.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val settingsCount = when {
                        state.settingsState.isLoading -> "Loading..."
                        state.settingsState.isError -> "Error"
                        else -> state.settingsState.dataOrNull?.size?.toString() ?: "0"
                    }

                    Text(
                        text = "Selected Model ID: ${state.selectedModelId ?: "None"}\n" +
                                "Available models: ${models.size}\n" +
                                "Settings profiles: $settingsCount\n\n" +
                                "Full settings management UI will be implemented in Phase 4.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            else -> {
                // Idle state
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
