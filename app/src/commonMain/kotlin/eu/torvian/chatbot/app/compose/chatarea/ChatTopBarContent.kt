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
import eu.torvian.chatbot.common.models.agent.AgentRoleDto


/**
 * Top bar content for the Chat screen.
 * Displays the agent-role selector, and in-session search.
 *
 * This composable is designed to work within a RowScope (app bar actions).
 *
 * @param userMenu trailing user menu composable supplied by the scaffold.
 * @param navItems navigation item composables rendered on the left side.
 * @param currentRole currently selected agent role for the session, or null when none is attached.
 * @param availableRoles load state for the user's agent roles.
 * @param onSelectRole selects an agent role for the current session.
 * @param onRetryLoadRoles retries loading agent roles after a failure.
 * @param onAddRole opens the add-role dialog for the current session.
 * @param onEditRole opens the edit-role dialog for the currently selected role.
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
    currentRole: AgentRoleDto?,
    availableRoles: DataState<RepositoryError, List<AgentRoleDto>>,
    onSelectRole: (Long?) -> Unit,
    onRetryLoadRoles: () -> Unit,
    onAddRole: () -> Unit,
    onEditRole: () -> Unit,
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
            // Agent role selector — replaces the old model/settings/tool-config controls.
            PlainTooltipBox(text = "Select Agent Role") {
                CompactAgentRoleSelector(
                    currentRole = currentRole,
                    availableRoles = availableRoles,
                    onSelectRole = onSelectRole,
                    onRetryLoadRoles = onRetryLoadRoles,
                    onAddRole = onAddRole,
                    onEditRole = onEditRole
                )
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
 * Compact agent-role selector for the top bar.
 *
 * Shows a text button with the currently selected role, or the "No role" placeholder when none is
 * selected (e.g. a brand-new session or an empty role catalog). The dropdown lists the available
 * roles plus quick "Add Role" / "Edit Role" actions that open the management dialogs directly on the
 * chat screen, so the user can tweak a role and keep chatting without leaving the conversation.
 * "No role" is a pure placeholder and never appears as a selectable entry.
 *
 * @param currentRole The role currently attached to the active session, or null when none is selected.
 * @param availableRoles Load state for the user's role catalog.
 * @param onSelectRole Callback invoked with the role id to select.
 * @param onRetryLoadRoles Callback invoked to retry a failed role load.
 * @param onAddRole Callback invoked to open the add-role dialog.
 * @param onEditRole Callback invoked to open the edit-role dialog for [currentRole]; only enabled
 *            while a role is selected.
 */
@Composable
private fun CompactAgentRoleSelector(
    currentRole: AgentRoleDto?,
    availableRoles: DataState<RepositoryError, List<AgentRoleDto>>,
    onSelectRole: (Long?) -> Unit,
    onRetryLoadRoles: () -> Unit,
    onAddRole: () -> Unit,
    onEditRole: () -> Unit
) {
    when (availableRoles) {
        is DataState.Success -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(
                        text = currentRole?.displayName
                            ?: currentRole?.name
                            ?: "No role",
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    availableRoles.data.forEach { role ->
                        DropdownMenuItem(
                            text = { Text(role.displayName ?: role.name) },
                            onClick = {
                                onSelectRole(role.id)
                                expanded = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add Role") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            onAddRole()
                            expanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit Role") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null
                            )
                        },
                        enabled = currentRole != null,
                        onClick = {
                            onEditRole()
                            expanded = false
                        }
                    )
                }
            }
        }

        is DataState.Loading -> {
            LoadingOverlay(Modifier.size(24.dp))
        }

        is DataState.Error -> {
            IconButton(onClick = onRetryLoadRoles) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry loading agent roles")
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
