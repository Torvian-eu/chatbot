package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
 * @param authState Authentication context for permission-sensitive model actions.
 * @param viewModel Model configuration ViewModel resolved from Koin.
 * @param modifier Modifier applied to the presentational tab.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the
 * current Models page in the breadcrumb trail.
 */
@Composable
fun ModelsTabRoute(
    authState: AuthState.Authenticated,
    viewModel: ModelConfigViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    var selectedModelId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Tab-local initial load.
    LaunchedEffect(Unit) {
        viewModel.loadModelsAndProviders()
    }

    // Collect tab state here.
    val modelConfigState by viewModel.modelConfigState.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val configData = (modelConfigState as? DataState.Success)?.data
    val activeModelDetails = configData?.models?.firstOrNull { it.model.id == selectedModelId }

    // If a model disappears while its detail page is open, fall back to the list page.
    LaunchedEffect(configData?.models, selectedModelId) {
        val models = configData?.models
        if (models != null && selectedModelId != null && models.none { it.model.id == selectedModelId }) {
            selectedModelId = null
            viewModel.selectModel(null)
        }
    }

    val breadcrumbs = if (activeModelDetails != null) {
        listOf(
            "Settings",
            SettingsCategory.Models.displayLabel,
            activeModelDetails.model.displayName?.takeIf { it.isNotBlank() } ?: activeModelDetails.model.name
        )
    } else {
        listOf("Settings", SettingsCategory.Models.displayLabel)
    }

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    // Build presentational state.
    val state = ModelsTabState(
        modelConfigUiState = modelConfigState,
        selectedModel = activeModelDetails,
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
            selectedModelId = modelDetails.model.id
            actions.onSelectModel(modelDetails)
        },
        onBackToModelList = {
            selectedModelId = null
            actions.onSelectModel(null)
        },
        modifier = modifier
    )
}
