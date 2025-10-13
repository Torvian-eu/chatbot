package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import org.koin.compose.viewmodel.koinViewModel
import eu.torvian.chatbot.app.repository.AuthState

/**
 * Route composable for the Models tab that manages its own ViewModel and state.
 * This follows the Route pattern for better modularity and testability.
 */
@Composable
fun ModelsTabRoute(
    authState: AuthState.Authenticated,
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
        override fun onSelectModel(modelDetails: LLMModelDetails?) = viewModel.selectModel(modelDetails)
        override fun onUpdateModelForm(update: (ModelFormState) -> ModelFormState) =
            viewModel.updateModelForm(update)
        override fun onCancelDialog() = viewModel.cancelDialog()

        // Access management actions
        override fun onMakeModelPublic(modelDetails: LLMModelDetails) =
            viewModel.makeModelPublic(modelDetails)
        override fun onMakeModelPrivate(modelDetails: LLMModelDetails) =
            viewModel.makeModelPrivate(modelDetails)
        override fun onOpenManageAccessDialog(modelDetails: LLMModelDetails) =
            viewModel.openManageAccessDialog(modelDetails)
        override fun onOpenGrantAccessDialog() = viewModel.openGrantAccessDialog()
        override fun onCloseGrantAccessDialog() = viewModel.closeGrantAccessDialog()
        override fun onUpdateGrantAccessForm(form: GrantAccessFormState) =
            viewModel.updateGrantAccessForm { form }
        override fun onGrantModelAccess(modelId: Long, groupId: Long, accessMode: String) =
            viewModel.grantModelAccess(modelId, groupId, accessMode)
        override fun onRevokeModelAccess(modelId: Long, groupId: Long, accessMode: String) =
            viewModel.revokeModelAccess(modelId, groupId, accessMode)
    }

    // Call the presentational ModelsTab
    ModelsTab(
        state = state,
        actions = actions,
        authState = authState,
        modifier = modifier
    )
}
