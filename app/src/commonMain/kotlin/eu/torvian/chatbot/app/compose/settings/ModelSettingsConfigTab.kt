package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.common.models.api.access.ModelSettingsDetails

/**
 * Model Settings tab with separate list and details pages.
 *
 * The route owns the page id and keeps the selected model context separate from
 * the opened settings-profile page while the ViewModel continues to own dialogs.
 *
 * @param state Current Model Settings state supplied by the route.
 * @param actions ViewModel-forwarding actions for model-context and settings flows.
 * @param authState Authentication context used to gate create-settings actions.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun ModelSettingsConfigTab(
    state: ModelSettingsConfigTabState,
    actions: ModelSettingsConfigTabActions,
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val modelsState = state.modelsUiState) {
            is DataState.Loading -> LoadingStateDisplay("Loading models and settings...")
            is DataState.Error -> ErrorStateDisplay(
                title = "Failed to load configuration",
                error = modelsState.error,
                onRetry = { actions.onLoadModelsAndSettings() }
            )

            is DataState.Success -> {
                val settingsList = state.settingsListForSelectedModel ?: emptyList()

                if (state.selectedSettings != null) {
                    ModelSettingsDetailsPage(
                        selectedModel = state.selectedModel,
                        settingsDetails = state.selectedSettings,
                        onBackToList = { actions.onSelectSettings(null) },
                        onEdit = actions::onStartEditingSettings,
                        onDelete = actions::onStartDeletingSettings,
                        onMakePublic = actions::onMakeSettingsPublic,
                        onMakePrivate = actions::onMakeSettingsPrivate,
                        onManageAccess = actions::onOpenManageAccessDialog,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ModelSettingsListPage(
                        models = modelsState.data,
                        selectedModel = state.selectedModel,
                        settingsList = settingsList,
                        selectedSettings = state.selectedSettings,
                        onModelSelected = actions::onSelectModel,
                        onSettingsSelected = { settingsDetails: ModelSettingsDetails ->
                            actions.onSelectSettings(settingsDetails)
                        },
                        onAddNewSettings = actions::onStartAddingNewSettings,
                        authState = authState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is DataState.Idle -> {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Button(onClick = { actions.onLoadModelsAndSettings() }) {
                        Text("Load Settings Configuration")
                    }
                }
            }
        }

        // Dialogs will be handled here, based on state.dialogState
        SettingsDialogs(
            dialogState = state.dialogState,
            actions = actions
        )
    }
}
