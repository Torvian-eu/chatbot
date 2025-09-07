package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
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
 * Models management tab with master-detail layout.
 * Implements Epic 4 user stories: E4.S1-S4.
 */
@Composable
fun ModelsTab(
    state: ModelsTabState,
    actions: ModelsTabActions,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.modelConfigUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading models...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load models",
                    error = uiState.error,
                    onRetry = { actions.onLoadModelsAndProviders() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val configData = uiState.data
                val models = configData.models
                val providers = configData.providers

                Row(modifier = Modifier.fillMaxSize()) {
                    // Master: Models List
                    ModelsListPanel(
                        models = models,
                        selectedModel = state.selectedModel,
                        onModelSelected = { actions.onSelectModel(it) },
                        onAddNewModel = { actions.onStartAddingNewModel() },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    // Detail: Model Details/Edit
                    ModelDetailPanel(
                        model = state.selectedModel,
                        onEditModel = { actions.onStartEditingModel(it) },
                        onDeleteModel = { model ->
                            actions.onStartDeletingModel(model)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 16.dp),
                        providers = providers
                    )
                }
            }

            is DataState.Idle -> {
                // Idle state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Click to load models",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadModelsAndProviders() }) {
                            Text("Load Models")
                        }
                    }
                }
            }
        }
    }

    ModelsDialogs(
        dialogState = state.dialogState,
        state = state,
        actions = actions
    )
}
