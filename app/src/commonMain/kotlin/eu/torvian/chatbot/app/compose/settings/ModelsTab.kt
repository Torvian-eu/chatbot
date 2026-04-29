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
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails

/**
 * Models management tab with separate list and details pages.
 *
 * The tab remains presentational: it switches between page-sized content while the
 * route keeps page navigation state and the ViewModel continues to own dialog state.
 *
 * @param state Current Models tab state from the route.
 * @param actions ViewModel-forwarding actions for model CRUD and access flows.
 * @param authState Authentication state used to gate model creation.
 * @param onOpenModelDetails Callback invoked when the user opens a model details page.
 * @param onBackToModelList Callback invoked when the user returns to the model list.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun ModelsTab(
    state: ModelsTabState,
    actions: ModelsTabActions,
    authState: AuthState.Authenticated,
    onOpenModelDetails: (LLMModelDetails) -> Unit,
    onBackToModelList: () -> Unit,
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
                val activeModelDetails = state.selectedModel

                if (activeModelDetails != null) {
                    ModelDetailsPage(
                        modelDetails = activeModelDetails,
                        onBackToList = onBackToModelList,
                        onEditModel = { actions.onStartEditingModel(it) },
                        onDeleteModel = { actions.onStartDeletingModel(it) },
                        onMakePublic = { actions.onMakeModelPublic(it) },
                        onMakePrivate = { actions.onMakeModelPrivate(it) },
                        onManageAccess = { actions.onOpenManageAccessDialog(it) },
                        providers = providers,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ModelsListPage(
                        models = models,
                        selectedModel = activeModelDetails,
                        onModelSelected = { modelDetails ->
                            onOpenModelDetails(modelDetails)
                        },
                        onAddNewModel = { actions.onStartAddingNewModel() },
                        authState = authState,
                        modifier = Modifier.fillMaxSize()
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
