package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModelType
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A composite UI component for selecting the LLM model and its corresponding settings profile.
 *
 * This component is designed to be resilient and handles its own internal states for loading and errors
 * for both models and settings lists independently. This prevents a failure in loading, for example,
 * the settings list from disrupting the model selection or the main chat view.
 *
 * @param currentModel The currently selected LLM model.
 * @param currentSettings The currently selected settings profile.
 * @param availableModels The state of the list of all available models.
 * @param availableSettings The state of the list of settings available for the current model.
 * @param onSelectModel Callback triggered when a user selects a model from the dropdown.
 * @param onSelectSettings Callback triggered when a user selects a settings profile.
 * @param onRetryLoadModels Callback to retry loading the model list if it failed.
 * @param onRetryLoadSettings Callback to retry loading the settings list if it failed.
 * @param modifier The modifier to be applied to the component.
 */
@Composable
fun ModelSettingsSelector(
    currentModel: LLMModel?,
    currentSettings: ModelSettings?,
    availableModels: DataState<RepositoryError, List<LLMModel>>,
    availableSettings: DataState<RepositoryError, List<ModelSettings>>,
    onSelectModel: (Long) -> Unit,
    onSelectSettings: (Long) -> Unit,
    onRetryLoadModels: () -> Unit,
    onRetryLoadSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // --- Model Dropdown ---
        SingleSelector(
            label = "Model",
            dataState = availableModels,
            onRetry = onRetryLoadModels,
            modifier = Modifier.weight(1f)
        ) { models ->
            var expanded by remember { mutableStateOf(false) }
            SelectorDropdown(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                currentValueDisplay = currentModel?.displayName ?: currentModel?.name ?: "Select a Model",
                items = models,
                itemLabel = { it.displayName ?: it.name },
                onItemSelected = {
                    onSelectModel(it.id)
                    expanded = false
                }
            )
        }

        // --- Settings Dropdown ---
        SingleSelector(
            label = "Settings Profile",
            dataState = availableSettings,
            onRetry = onRetryLoadSettings,
            modifier = Modifier.weight(1f)
        ) { settings ->
            var expanded by remember { mutableStateOf(false) }
            SelectorDropdown(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                currentValueDisplay = currentSettings?.name ?: "Select a Settings Profile",
                items = settings,
                itemLabel = { it.name },
                onItemSelected = {
                    onSelectSettings(it.id)
                    expanded = false
                }
            )
        }
    }
}

/**
 * A reusable helper composable that encapsulates the logic for displaying a
 * selector in its Loading, Error, or Success state.
 *
 * @param T The type of data in the list.
 * @param label The text label to display above the selector.
 * @param dataState The DataState object that drives the UI.
 * @param onRetry The callback to invoke when the retry button is clicked in the error state.
 * @param modifier The modifier for the component.
 * @param successContent The composable lambda to render when the dataState is Success. It receives the loaded data list.
 */
@Composable
private fun <T> SingleSelector(
    label: String,
    dataState: DataState<RepositoryError, List<T>>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    successContent: @Composable (data: List<T>) -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            when (dataState) {
                is DataState.Idle, is DataState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.Center))
                }

                is DataState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Load failed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                        IconButton(onClick = onRetry) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry loading $label",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                is DataState.Success -> {
                    successContent(dataState.data)
                }
            }
        }
    }
}


/**
 * A generic dropdown menu component.
 *
 * @param T The type of item in the list.
 * @param expanded Whether the dropdown menu is currently visible.
 * @param onExpandedChange Callback to change the expanded state.
 * @param currentValueDisplay The text to display on the button.
 * @param items The list of items to display in the dropdown.
 * @param itemLabel A function to get the string label for an item.
 * @param onItemSelected The callback invoked when an item is selected.
 */
@Composable
private fun <T> SelectorDropdown(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    currentValueDisplay: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onItemSelected: (T) -> Unit
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = currentValueDisplay,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Open dropdown")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.fillMaxWidth(0.4f) // Adjust width as needed
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = { onItemSelected(item) }
                )
            }
        }
    }
}

// --- Previews for different states ---

@Preview
@Composable
private fun ModelSettingsSelector_SuccessState_Preview() {
    val models = listOf(
        LLMModel(1, "GPT-4 Turbo", 1, true, "GPT-4 Turbo", LLMModelType.CHAT),
        LLMModel(2, "Claude 3 Sonnet", 2, true, "Claude 3 Sonnet", LLMModelType.CHAT)
    )
    val settings = listOf(
        ChatModelSettings(10, 1, "Creative"),
        ChatModelSettings(11, 1, "Balanced")
    )
    MaterialTheme {
        ModelSettingsSelector(
            currentModel = models.first(),
            currentSettings = settings.last(),
            availableModels = DataState.Success(models),
            availableSettings = DataState.Success(settings),
            onSelectModel = {},
            onSelectSettings = {},
            onRetryLoadModels = {},
            onRetryLoadSettings = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview
@Composable
private fun ModelSettingsSelector_LoadingState_Preview() {
    MaterialTheme {
        ModelSettingsSelector(
            currentModel = null,
            currentSettings = null,
            availableModels = DataState.Loading,
            availableSettings = DataState.Loading,
            onSelectModel = {},
            onSelectSettings = {},
            onRetryLoadModels = {},
            onRetryLoadSettings = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}


@Preview
@Composable
private fun ModelSettingsSelector_ErrorState_Preview() {
    MaterialTheme {
        ModelSettingsSelector(
            currentModel = null,
            currentSettings = null,
            availableModels = DataState.Error(RepositoryError.OtherError("Network timeout")),
            availableSettings = DataState.Error(RepositoryError.OtherError("Server error")),
            onSelectModel = {},
            onSelectSettings = {},
            onRetryLoadModels = {},
            onRetryLoadSettings = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview
@Composable
private fun ModelSettingsSelector_MixedState_Preview() {
    val models = listOf(
        LLMModel(1, "GPT-4 Turbo", 1, true, "GPT-4 Turbo", LLMModelType.CHAT)
    )
    MaterialTheme {
        ModelSettingsSelector(
            currentModel = models.first(),
            currentSettings = null,
            availableModels = DataState.Success(models), // Models loaded
            availableSettings = DataState.Error(RepositoryError.OtherError("Not found")), // Settings failed
            onSelectModel = {},
            onSelectSettings = {},
            onRetryLoadModels = {},
            onRetryLoadSettings = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
