package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
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
import eu.torvian.chatbot.app.compose.auth.AuthDialogs
import eu.torvian.chatbot.app.compose.auth.LoginScreen
import eu.torvian.chatbot.app.compose.auth.RegisterScreen
import eu.torvian.chatbot.app.compose.common.OverflowTooltipText
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.app.compose.settings.SettingsScreen
import eu.torvian.chatbot.app.compose.snackbar.SharedSnackbar
import eu.torvian.chatbot.app.compose.snackbar.SnackbarVisualsWithError
import eu.torvian.chatbot.app.domain.navigation.*
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

    // Collect account switching state
    val availableAccounts by authViewModel.availableAccounts.collectAsState()
    val accountSwitchInProgress by authViewModel.accountSwitchInProgress.collectAsState()
    val dialogState by authViewModel.dialogState.collectAsState()

    // Load initial data for authenticated user (improved data loading)
    LaunchedEffect(authState.userId) {
        sessionListViewModel.loadSessionsAndGroups()
        navController.navigate(Chat) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
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
                            availableAccounts = availableAccounts,
                            accountSwitchInProgress = accountSwitchInProgress,
                            onSwitchAccount = { authViewModel.openAccountSwitcher() },
                            onAddAccount = { authViewModel.openAddAccount() },
                            onLogout = {
                                scope.launch { authViewModel.logout() }
                            },
                            onLogin = {
                                navController.navigate(Login) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
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
                        ChatScreen(sessionListViewModel, chatViewModel, authState)
                    }
                    composable<Settings> {
                        SettingsScreen(authState = authState)
                    }
                    composable<Admin> {
                        AdminScreen(authState = authState)
                    }
                    composable<Login> {
                        LoginScreen(
                            onNavigateToRegister = {
                                navController.navigate(Register) {
                                    // Don't add to back stack to prevent back navigation to login
                                    popUpTo(Login) { inclusive = true }
                                }
                            },
                            authViewModel = authViewModel
                        )
                    }
                    composable<Register> {
                        RegisterScreen(
                            onNavigateToLogin = {
                                navController.navigate(Login) {
                                    popUpTo(Register) { inclusive = true }
                                }
                            },
                            onRegistrationSuccess = {
                                navController.navigate(Login) {
                                    popUpTo(Register) { inclusive = true }
                                }
                            },
                            authViewModel = authViewModel
                        )
                    }
                }
            }
        }

        // Render authentication dialogs
        AuthDialogs(
            dialogState = dialogState,
            availableAccounts = availableAccounts,
            currentAuthState = authState,
            accountSwitchInProgress = accountSwitchInProgress,
            authViewModel = authViewModel
        )
    }
}
