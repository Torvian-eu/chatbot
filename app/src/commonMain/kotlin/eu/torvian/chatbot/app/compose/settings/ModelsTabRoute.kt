package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.GrantAccessFormState
import eu.torvian.chatbot.app.domain.contracts.ModelFormState
import eu.torvian.chatbot.app.viewmodel.ModelConfigViewModel
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.llm.LLMModel
import org.koin.compose.viewmodel.koinViewModel
import eu.torvian.chatbot.app.repository.AuthState

/**
 * Route composable for the Models settings category.
 *
 * The route keeps the ViewModel wiring, model-local page state, and breadcrumb
 * updates together so the page switch stays separate from dialog state.
 *
 * Selection state is owned by the [ModelConfigViewModel]; this route only
 * observes [ModelConfigViewModel.selectedModel] to decide whether to show
 * the list or detail page.
 *
 * @param authState Authentication context for permission-sensitive model actions.
 * @param viewModel Model configuration ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param categoryResetSignal Incremented when the user re-selects this category
 *   in the sidebar; triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 *   current Models page in the breadcrumb trail.
 */
@Composable
fun ModelsTabRoute(
    authState: AuthState.Authenticated,
    viewModel: ModelConfigViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load.
    LaunchedEffect(Unit) {
        viewModel.loadModelsAndProviders()
    }

    // Reset to list view when the category is re-selected in the sidebar.
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.selectModel(null)
        }
    }

    // Collect tab state here.
    val modelConfigState by viewModel.modelConfigState.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val configData = (modelConfigState as? DataState.Success)?.data

    // If a model disappears while its detail page is open, fall back to the list page.
    LaunchedEffect(configData?.models, selectedModel) {
        val models = configData?.models
        val selectedModelId = selectedModel?.model?.id
        if (models != null && selectedModelId != null && models.none { it.model.id == selectedModelId }) {
            viewModel.selectModel(null)
        }
    }

    val breadcrumbs = selectedModel?.let {
        listOf(
            "Settings",
            SettingsCategory.Models.displayLabel,
            it.model.displayName?.takeIf { it.isNotBlank() } ?: it.model.name
        )
    } ?: listOf("Settings", SettingsCategory.Models.displayLabel)

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    // Build presentational state.
    val state = ModelsTabState(
        modelConfigUiState = modelConfigState,
        selectedModel = selectedModel,
        dialogState = dialogState
    )

    // Build actions forwarding to VM.
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

    // Call the presentational ModelsTab.
    ModelsTab(
        state = state,
        actions = actions,
        authState = authState,
        onOpenModelDetails = { modelDetails ->
            viewModel.selectModel(modelDetails)
        },
        onBackToModelList = {
            viewModel.selectModel(null)
        },
        modifier = modifier
    )
}
