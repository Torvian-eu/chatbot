package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.torvian.chatbot.app.compose.admin.AdminScreen
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.app.compose.settings.SettingsScreen
import eu.torvian.chatbot.app.compose.snackbar.SharedSnackbar
import eu.torvian.chatbot.app.compose.snackbar.SnackbarVisualsWithError
import eu.torvian.chatbot.app.domain.navigation.Admin
import eu.torvian.chatbot.app.domain.navigation.Chat
import eu.torvian.chatbot.app.domain.navigation.Settings
import eu.torvian.chatbot.app.generated.resources.Res
import eu.torvian.chatbot.app.generated.resources.app_name
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import eu.torvian.chatbot.app.viewmodel.chat.ChatViewModel
import eu.torvian.chatbot.common.api.CommonPermissions
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Main application flow for authenticated users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApplicationFlow(
    authState: AuthState.Authenticated,
    snackbarHostState: SnackbarHostState,
    authViewModel: AuthViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val sessionListViewModel: SessionListViewModel = koinViewModel()
    val chatViewModel: ChatViewModel = koinViewModel()
    val scope = rememberCoroutineScope()

    // Load initial data for authenticated user (improved data loading)
    LaunchedEffect(authState.userId) {
        sessionListViewModel.loadSessionsAndGroups()
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        OverflowTooltipText(
                            text = "${stringResource(Res.string.app_name)} - ${authState.username}"
                        )
                    },
                    actions = {
                        UserMenu(
                            username = authState.username,
                            onLogout = {
                                scope.launch { authViewModel.logout() }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                        label = { Text("Chat") },
                        selected = currentRoute == Chat.route,
                        onClick = {
                            navController.navigate(Chat) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == Settings.route,
                        onClick = {
                            navController.navigate(Settings) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )

                    // Admin navigation item with permission check
                    RequiresAnyPermission(
                        authState = authState,
                        permissions = listOf(
                            CommonPermissions.MANAGE_USERS,
                            CommonPermissions.MANAGE_ROLES
                        )
                    ) {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = "Admin") },
                            label = { Text("Admin") },
                            selected = currentRoute == Admin.route,
                            onClick = {
                                navController.navigate(Admin) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    val visualsWithError = data.visuals as? SnackbarVisualsWithError
                    SharedSnackbar(data, visualsWithError)
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                NavHost(navController = navController, startDestination = Chat) {
                    composable<Chat> {
                        ChatScreen(sessionListViewModel, chatViewModel)
                    }
                    composable<Settings> {
                        SettingsScreen()
                    }
                    composable<Admin> {
                        AdminScreen(authState = authState)
                    }
                }
            }
        }
    }
}

/**
 * User menu dropdown for authenticated users.
 */
@Composable
private fun UserMenu(
    username: String,
    onLogout: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "User menu"
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // User info header
            DropdownMenuItem(
                text = {
                    Text(
                        text = username,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                onClick = { /* Future: navigate to profile */ },
                leadingIcon = {
                    Icon(Icons.Default.AccountCircle, contentDescription = null)
                }
            )

            HorizontalDivider()

            // Logout option
            DropdownMenuItem(
                text = { Text("Logout") },
                onClick = {
                    expanded = false
                    onLogout()
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                }
            )
        }
    }
}