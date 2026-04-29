package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails

/**
 * Full-width page for browsing configured models.
 *
 * The page now owns the shared shell, header copy, and add action while the list
 * panel focuses on row rendering and empty-state behavior.
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
    SettingsListPageTemplate(
        title = "Models",
        subtitle = if (models.isEmpty()) {
            "No models configured yet. Use the add action to create your first model."
        } else {
            "${models.size} configured • select a model to view its details."
        },
        modifier = modifier,
        actions = {
            RequiresAnyPermission(
                authState = authState,
                permissions = listOf(CommonPermissions.CREATE_LLM_MODEL, CommonPermissions.MANAGE_LLM_MODELS)
            ) {
                FilledTonalButton(onClick = onAddNewModel) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add model", maxLines = 1, softWrap = false)
                }
            }
        }
    ) {
        ModelsListPanel(
            models = models,
            selectedModel = selectedModel,
            onModelSelected = onModelSelected,
            modifier = Modifier.fillMaxSize()
        )
    }
}

