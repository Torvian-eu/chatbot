package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.common.models.llm.LLMModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Models tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun ModelsTabRoute(
    viewModel: ModelConfigViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    // Tab-local initial load
    LaunchedEffect(Unit) {
        viewModel.loadModelsAndProviders()
    }

    // Collect tab state here
    val modelConfigState by viewModel.modelConfigState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()

    // Build presentational state
    val state = ModelsTabState(
        modelConfigUiState = modelConfigState,
        selectedModel = selectedModel,
        dialogState = dialogState
    )

    // Build actions forwarding to VM
    val actions = object : ModelsTabActions {
        override fun onLoadModelsAndProviders() = viewModel.loadModelsAndProviders()
        override fun onStartAddingNewModel() = viewModel.startAddingNewModel()
        override fun onSaveModel() = viewModel.saveModel()
        override fun onStartEditingModel(model: LLMModel) = viewModel.startEditingModel(model)
        override fun onStartDeletingModel(model: LLMModel) = viewModel.startDeletingModel(model)
        override fun onDeleteModel(modelId: Long) = viewModel.deleteModel(modelId)
        override fun onSelectModel(model: LLMModel?) = viewModel.selectModel(model)
        override fun onUpdateModelForm(update: (ModelFormState) -> ModelFormState) =
            viewModel.updateModelForm(update)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    // Call the presentational ModelsTab
    ModelsTab(
        state = state,
        actions = actions,
        modifier = modifier
    )
}
