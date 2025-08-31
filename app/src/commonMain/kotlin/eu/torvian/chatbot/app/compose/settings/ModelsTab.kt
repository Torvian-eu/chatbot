package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.UiState
import eu.torvian.chatbot.common.models.LLMModel

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
    var modelToDelete by remember { mutableStateOf<LLMModel?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.modelConfigUiState) {
            is UiState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading models...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is UiState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load models",
                    error = uiState.error,
                    onRetry = { actions.onLoadModelsAndProviders() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is UiState.Success -> {
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
                        onDeleteModel = { modelToDelete = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 16.dp),
                        providers = providers
                    )
                }
            }

            is UiState.Idle -> {
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

        // Add New Model Dialog
        if (state.isAddingNewModel) {
            when (val uiState = state.modelConfigUiState) {
                is UiState.Success -> {
                    ModelFormDialog(
                        title = "Add New Model",
                        form = state.modelForm,
                        modelConfigData = uiState.data,
                        onFormUpdate = { update -> actions.onUpdateModelForm(update) },
                        onSave = { actions.onSaveModel() },
                        onCancel = { actions.onCancelAddingNewModel() }
                    )
                }
                else -> {
                    // Don't show dialog if data isn't loaded
                }
            }
        }

        // Edit Model Dialog
        if (state.editingModel != null) {
            when (val uiState = state.modelConfigUiState) {
                is UiState.Success -> {
                    ModelFormDialog(
                        title = "Edit Model",
                        form = state.modelForm,
                        modelConfigData = uiState.data,
                        onFormUpdate = { update -> actions.onUpdateModelForm(update) },
                        onSave = { actions.onSaveModel() },
                        onCancel = { actions.onCancelEditingModel() }
                    )
                }
                else -> {
                    // Don't show dialog if data isn't loaded
                }
            }
        }

        // Delete Confirmation Dialog
        modelToDelete?.let { model ->
            AlertDialog(
                onDismissRequest = { modelToDelete = null },
                title = { Text("Delete Model") },
                text = {
                    Text("Are you sure you want to delete the model '${model.displayName ?: model.name}'? This action cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            actions.onDeleteModel(model.id)
                            modelToDelete = null
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { modelToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
