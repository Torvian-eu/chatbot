package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails
import eu.torvian.chatbot.common.models.llm.LLMModel

/**
 * Full-width page for the Model Settings profiles list.
 *
 * The page keeps the current model context visible above the list so users can
 * see which model they are configuring even before opening a profile.
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
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Model Settings",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = selectedModel?.let { model ->
                        "Configuring settings profiles for ${model.displayName?.takeIf { it.isNotBlank() } ?: model.name}."
                    } ?: "Select a model to view and manage its settings profiles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ModelSettingsListPanel(
            models = models,
            selectedModel = selectedModel,
            settingsList = settingsList,
            selectedSettings = selectedSettings,
            onModelSelected = onModelSelected,
            onSettingsSelected = onSettingsSelected,
            onAddNewSettings = onAddNewSettings,
            authState = authState,
            modifier = Modifier.fillMaxSize()
        )
    }
}



