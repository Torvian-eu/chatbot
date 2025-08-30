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
 * Models management tab with ViewModel integration.
 * Demonstrates proper state collection and error handling.
 * Full implementation will be completed in Phase 3.
 */
@Composable
fun ModelsTab(
    state: ModelsTabState,
    actions: ModelsTabActions,
    modifier: Modifier = Modifier
) {
    when {
        state.modelsUiState.isLoading -> {
            LoadingStateDisplay(
                message = "Loading models...",
                modifier = modifier.fillMaxSize()
            )
        }

        state.modelsUiState.isError -> {
            val error = state.modelsUiState.errorOrNull
            if (error != null) {
                ErrorStateDisplay(
                    title = "Failed to load models",
                    error = error,
                    onRetry = { actions.onLoadModelsAndProviders() },
                    modifier = modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = "Unknown error occurred",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = modifier.fillMaxSize()
                )
            }
        }

        state.modelsUiState.isSuccess -> {
            val models = state.modelsUiState.dataOrNull ?: emptyList()

            Column(modifier = modifier.fillMaxSize()) {
                Text(
                    text = "Models (${models.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (models.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No models configured.\nFull UI will be implemented in Phase 3.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "Found ${models.size} models:\n${models.joinToString("\n") { model -> "• ${model.name} (${model.displayName ?: "No display name"})" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        else -> {
            // Idle state
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { actions.onLoadModelsAndProviders() }) {
                    Text("Load Models")
                }
            }
        }
    }
}
