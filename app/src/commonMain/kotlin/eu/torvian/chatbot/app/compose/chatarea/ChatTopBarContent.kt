package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings

/**
 * Top bar content for the Chat screen.
 * Displays model selection, settings selection, and tool configuration.
 *
 * This composable is designed to work within a RowScope (app bar actions).
 */
@Composable
fun RowScope.ChatTopBarContent(
    userMenu: @Composable () -> Unit,
    navItems: List<@Composable () -> Unit>,
    currentModel: LLMModel?,
    currentSettings: ModelSettings?,
    availableModels: DataState<RepositoryError, List<LLMModel>>,
    availableSettings: DataState<RepositoryError, List<ModelSettings>>,
    onSelectModel: (Long) -> Unit,
    onSelectSettings: (Long) -> Unit,
    onRetryLoadModels: () -> Unit,
    onRetryLoadSettings: () -> Unit,
    onShowToolConfig: () -> Unit,
    enabledToolsCount: Int,
    isSessionListCollapsed: Boolean,
    onToggleSessionList: () -> Unit,
    onCopyThread: () -> Unit
) {
    // Left-aligned actions
    Row(
        horizontalArrangement = Arrangement.Start
    ) {
        // Session list panel toggle button
        PlainTooltipBox(
            text = if (isSessionListCollapsed) "Show session list" else "Hide session list"
        ) {
            IconButton(
                onClick = onToggleSessionList,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (isSessionListCollapsed) Modifier.rotate(180f) else Modifier)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuOpen,
                    contentDescription = if (isSessionListCollapsed) "Show session list" else "Hide session list"
                )
            }
        }

        // Navigation items
        navItems.forEach {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }

    // Center-aligned actions
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.Center
    ) {
        // Compact model selector (icon + dropdown)
        PlainTooltipBox(text = "Select Model") {
            CompactModelSelector(
                currentModel = currentModel,
                availableModels = availableModels,
                onSelectModel = onSelectModel,
                onRetryLoadModels = onRetryLoadModels
            )
        }

        Spacer(Modifier.width(8.dp))

        // Compact settings selector (icon + dropdown)
        PlainTooltipBox(text = "Select Model Settings") {
            CompactSettingsSelector(
                currentSettings = currentSettings,
                availableSettings = availableSettings,
                onSelectSettings = onSelectSettings,
                onRetryLoadSettings = onRetryLoadSettings
            )
        }

        Spacer(Modifier.width(8.dp))

        // Tool configuration button with badge
        PlainTooltipBox(text = "Configure Tools") {
            BadgedBox(
                badge = {
                    if (enabledToolsCount > 0) {
                        Badge {
                            Text(enabledToolsCount.toString())
                        }
                    }
                }
            ) {
                IconButton(
                    onClick = onShowToolConfig,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Configure Tools"
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // More actions menu
        MoreActionsMenu(onCopyThread = onCopyThread)
    }

    // User menu
    Row(
        horizontalArrangement = Arrangement.End
    ) {
        Spacer(Modifier.width(8.dp))
        userMenu()
    }
}

/**
 * Compact version of the model selector for the top bar.
 * Shows a text button that opens a dropdown menu.
 */
@Composable
private fun CompactModelSelector(
    currentModel: LLMModel?,
    availableModels: DataState<RepositoryError, List<LLMModel>>,
    onSelectModel: (Long) -> Unit,
    onRetryLoadModels: () -> Unit
) {
    when (availableModels) {
        is DataState.Success -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(
                        text = currentModel?.displayName ?: currentModel?.name ?: "Model",
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableModels.data.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName ?: model.name) },
                            onClick = {
                                onSelectModel(model.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        is DataState.Loading -> {
            LoadingOverlay(Modifier.size(24.dp))
        }

        is DataState.Error -> {
            IconButton(onClick = onRetryLoadModels) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry loading models")
            }
        }

        is DataState.Idle -> {
            // Show nothing or placeholder
        }
    }
}

/**
 * Compact version of the settings selector for the top bar.
 * Shows a text button that opens a dropdown menu.
 */
@Composable
private fun CompactSettingsSelector(
    currentSettings: ModelSettings?,
    availableSettings: DataState<RepositoryError, List<ModelSettings>>,
    onSelectSettings: (Long) -> Unit,
    onRetryLoadSettings: () -> Unit
) {
    when (availableSettings) {
        is DataState.Success -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(
                        text = currentSettings?.name ?: "Settings",
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableSettings.data.forEach { settings ->
                        DropdownMenuItem(
                            text = { Text(settings.name) },
                            onClick = {
                                onSelectSettings(settings.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        is DataState.Loading -> {
            LoadingOverlay(Modifier.size(24.dp))
        }

        is DataState.Error -> {
            IconButton(onClick = onRetryLoadSettings) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry loading settings")
            }
        }

        is DataState.Idle -> {
            // Show nothing or placeholder
        }
    }
}

/**
 * More actions menu for the chat top bar.
 * Currently includes Copy Thread action.
 */
@Composable
private fun MoreActionsMenu(
    onCopyThread: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    PlainTooltipBox(text = "More actions") {
        Box {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More actions"
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Copy Thread") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        onCopyThread()
                        expanded = false
                    }
                )
            }
        }
    }
}

