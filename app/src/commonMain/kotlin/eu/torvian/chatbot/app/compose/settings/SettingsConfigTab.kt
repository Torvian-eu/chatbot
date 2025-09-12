package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState

/**
 * Settings management tab with master-detail layout.
 * Implements Epic 4 user stories: E4.S5-S6.
 */
@Composable
fun SettingsConfigTab(
    state: SettingsConfigTabState,
    actions: SettingsConfigTabActions,
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
                Row(modifier = Modifier.fillMaxSize()) {
                    // Master Panel (Left)
                    SettingsListPanel(
                        models = modelsState.data,
                        selectedModel = state.selectedModel,
                        settingsList = state.settingsListForSelectedModel ?: emptyList(),
                        selectedSettings = state.selectedSettings,
                        onModelSelected = actions::onSelectModel,
                        onSettingsSelected = actions::onSelectSettings,
                        onAddNewSettings = actions::onStartAddingNewSettings,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    // Detail Panel (Right)
                    SettingsDetailPanel(
                        settings = state.selectedSettings,
                        onEdit = actions::onStartEditingSettings,
                        onDelete = actions::onStartDeletingSettings,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp)
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
