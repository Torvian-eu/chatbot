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
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel

/**
 * Full-width page for the Model Settings profiles list.
 *
 * The page now owns the shared shell, selected-model context, and add action
 * while the list panel focuses on row rendering and empty-state behavior.
 *
 * @param models Available models loaded for the tab.
 * @param selectedModel The currently selected model context, if any.
 * @param settingsList Settings profiles that belong to the selected model.
 * @param selectedSettings The currently selected settings profile, used for row highlighting.
 * @param onModelSelected Callback used when the model context changes.
 * @param onSettingsSelected Callback used when a settings profile is opened.
 * @param onAddNewSettings Callback used to start the add-settings dialog flow.
 * @param authState Authentication context for permission-sensitive actions.
 * @param modifier Modifier applied to the page container.
 */
@Composable
fun ModelSettingsListPage(
    models: List<LLMModel>,
    selectedModel: LLMModel?,
    settingsList: List<ModelSettingsDetails>,
    selectedSettings: ModelSettingsDetails?,
    onModelSelected: (LLMModel?) -> Unit,
    onSettingsSelected: (ModelSettingsDetails) -> Unit,
    onAddNewSettings: () -> Unit,
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier
) {
    val selectedModelName = selectedModel?.let { model ->
        model.displayName?.takeIf { it.isNotBlank() } ?: model.name
    }

    SettingsListPageTemplate(
        title = "Model Settings",
        subtitle = if (selectedModelName == null) {
            "Select a model to view and manage its settings profiles."
        } else {
            "$selectedModelName • ${settingsList.size} profile(s) configured."
        },
        modifier = modifier,
        actions = {
            if (selectedModel != null) {
                RequiresAnyPermission(
                    authState = authState,
                    permissions = listOf(
                        CommonPermissions.CREATE_LLM_MODEL_SETTINGS,
                        CommonPermissions.MANAGE_LLM_MODEL_SETTINGS
                    )
                ) {
                    FilledTonalButton(onClick = onAddNewSettings) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add profile", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    ) {
        ModelSettingsListPanel(
            models = models,
            selectedModel = selectedModel,
            settingsList = settingsList,
            selectedSettings = selectedSettings,
            onModelSelected = onModelSelected,
            onSettingsSelected = onSettingsSelected,
            modifier = Modifier.fillMaxSize()
        )
    }
}



