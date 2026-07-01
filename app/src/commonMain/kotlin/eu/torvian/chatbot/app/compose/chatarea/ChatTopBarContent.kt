package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.chat.search.SearchDirection
import eu.torvian.chatbot.app.compose.common.LoadingOverlay
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.ModelSettings


/**
 * Top bar content for the Chat screen.
 * Displays model selection, settings selection, tool configuration, and in-session search.
 *
 * This composable is designed to work within a RowScope (app bar actions).
 *
 * @param userMenu trailing user menu composable supplied by the scaffold.
 * @param navItems navigation item composables rendered on the left side.
 * @param currentModel currently selected chat model.
 * @param currentSettings currently selected settings profile.
 * @param availableModels load state for available chat models.
 * @param availableSettings load state for settings available for the current model.
 * @param onSelectModel selects a model for the current session.
 * @param onSelectSettings selects a settings profile for the current session.
 * @param onRetryLoadModels retries loading models after a failure.
 * @param onRetryLoadSettings retries loading settings after a failure.
 * @param onShowToolConfig opens the tool configuration dialog.
 * @param enabledToolsCount number of tools enabled for the session.
 * @param isSessionListCollapsed whether the session list panel is collapsed.
 * @param onToggleSessionList toggles the session list panel.
 * @param onCopyThread copies the current displayed thread to the clipboard.
 * @param isSearchActive whether top-bar search mode is currently enabled.
 * @param searchQuery current in-session search query.
 * @param currentSearchIndex currently selected result index, or `-1` when none is selected.
 * @param searchResultsCount total number of matching occurrences in the current thread.
 * @param canReturnToPreviousThread whether the in-session search UI should offer a rollback action.
 * @param onShowSearch enables search mode.
 * @param onCloseSearch disables search mode and clears the current query.
 * @param onUpdateSearchQuery updates the current search query.
 * @param onNavigateSearchResult cycles through search results.
 * @param onJumpToSearchResult jumps directly to a search result by zero-based index.
 * @param onReturnToPreviousThread restores the previously displayed session/thread when available.
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
    onCopyThread: () -> Unit,
    isSearchActive: Boolean,
    searchQuery: String,
    currentSearchIndex: Int,
    searchResultsCount: Int,
    canReturnToPreviousThread: Boolean,
    onShowSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onUpdateSearchQuery: (String) -> Unit,
    onNavigateSearchResult: (SearchDirection) -> Unit,
    onJumpToSearchResult: (Int) -> Unit,
    onReturnToPreviousThread: () -> Unit,
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
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSearchActive) {
            SearchBar(
                query = searchQuery,
                currentIndex = currentSearchIndex,
                resultCount = searchResultsCount,
                canReturnToPreviousThread = canReturnToPreviousThread,
                onQueryChange = onUpdateSearchQuery,
                onNavigate = onNavigateSearchResult,
                onJumpToResult = onJumpToSearchResult,
                onReturnToPreviousThread = onReturnToPreviousThread,
                onClose = onCloseSearch,
                modifier = Modifier.weight(1f),
            )
        } else {
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
            MoreActionsMenu(
                onCopyThread = onCopyThread,
                onShowSearch = onShowSearch,
            )
        }
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
 * Includes thread-level utility actions such as copy and search.
 *
 * @param onCopyThread copies the currently displayed thread.
 * @param onShowSearch enables in-session search mode.
 */
@Composable
private fun MoreActionsMenu(
    onCopyThread: () -> Unit,
    onShowSearch: () -> Unit,
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
                    text = { Text("Search Messages") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        onShowSearch()
                        expanded = false
                    }
                )
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

