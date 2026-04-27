package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails

/**
 * Full-width page for browsing configured models.
 *
 * The page intentionally delegates list rendering to [ModelsListPanel] so the
 * existing row styling and permission-gated add action remain unchanged while
 * the surrounding tab switches between list and details pages.
 *
 * @param models Models to render in the list.
 * @param selectedModel Currently focused model, used only for row highlighting.
 * @param onModelSelected Callback invoked when the user opens a model detail page.
 * @param onAddNewModel Callback invoked when the user starts the add-model flow.
 * @param authState Authentication state used to gate model-creation actions.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ModelsListPage(
    models: List<LLMModelDetails>,
    selectedModel: LLMModelDetails?,
    onModelSelected: (LLMModelDetails) -> Unit,
    onAddNewModel: () -> Unit,
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier
) {
    ModelsListPanel(
        models = models,
        selectedModel = selectedModel,
        onModelSelected = onModelSelected,
        onAddNewModel = onAddNewModel,
        authState = authState,
        modifier = modifier.fillMaxSize()
    )
}

