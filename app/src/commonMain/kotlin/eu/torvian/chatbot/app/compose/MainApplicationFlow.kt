package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import eu.torvian.chatbot.app.compose.admin.AdminScreen
import eu.torvian.chatbot.app.compose.auth.AuthDialogs
import eu.torvian.chatbot.app.compose.auth.LoginScreen
import eu.torvian.chatbot.app.compose.auth.RegisterScreen
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox
import eu.torvian.chatbot.app.compose.permissions.RequiresAnyPermission
import eu.torvian.chatbot.app.compose.settings.SettingsScreen
import eu.torvian.chatbot.app.compose.snackbar.SharedSnackbar
import eu.torvian.chatbot.app.compose.snackbar.SnackbarVisualsWithError
import eu.torvian.chatbot.app.compose.topbar.LocalTopBarContent
import eu.torvian.chatbot.app.compose.topbar.TopBarContent
import eu.torvian.chatbot.app.compose.topbar.TopBarContentController
import eu.torvian.chatbot.app.domain.navigation.*
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.service.auth.AccountData
import eu.torvian.chatbot.app.viewmodel.SessionListViewModel
import eu.torvian.chatbot.app.viewmodel.auth.AuthViewModel
import eu.torvian.chatbot.common.api.CommonPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    val sessionListViewModel: SessionListViewModel = koinViewModel()
    val scope = rememberCoroutineScope()

    var topBarContent by remember { mutableStateOf<TopBarContent?>(null) }
    val topBarController = rememberTopBarController { topBarContent = it }

    val availableAccounts by authViewModel.availableAccounts.collectAsState()
    val accountSwitchInProgress by authViewModel.accountSwitchInProgress.collectAsState()
    val dialogState by authViewModel.dialogState.collectAsState()

    LaunchedEffect(authState.userId) {
        sessionListViewModel.loadSessionsAndGroups()
        navController.navigateToTop(Chat)
    }

    CompositionLocalProvider(LocalTopBarContent provides topBarController) {
        MainScaffold(
            topBarContent = topBarContent,
            snackbarHostState = snackbarHostState,
            authState = authState,
            availableAccounts = availableAccounts,
            accountSwitchInProgress = accountSwitchInProgress,
            authViewModel = authViewModel,
            navController = navController,
            scope = scope
        ) { paddingValues ->
            MainNavHost(
                navController = navController,
                paddingValues = paddingValues,
                sessionListViewModel = sessionListViewModel,
                authState = authState,
                authViewModel = authViewModel
            )
        }

        AuthDialogs(
            dialogState = dialogState,
            availableAccounts = availableAccounts,
            currentAuthState = authState,
            accountSwitchInProgress = accountSwitchInProgress,
            authViewModel = authViewModel
        )
    }
}

/**
 * Creates and remembers a TopBarContentController instance.
 *
 * @param onContentChange Callback invoked whenever the top bar content changes, providing the new content or null if cleared.
 * @return A TopBarContentController that can be used to set or clear the top bar content.
 */
@Composable
private fun rememberTopBarController(
    onContentChange: (TopBarContent?) -> Unit
): TopBarContentController = remember {
    object : TopBarContentController {
        var currentGeneration: Int = 0
        var currentContent: TopBarContent? = null

        override fun setContent(content: TopBarContent): Int {
            currentGeneration++
            currentContent = content
            onContentChange(content)
            return currentGeneration
        }

        override fun clearContent(generation: Int) {
            if (generation == currentGeneration) {
                currentContent = null
                onContentChange(null)
            }
        }
    }
}

/**
 * Helper extension to navigate to a destination as a top-level route.
 */
private fun NavController.navigateToTop(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Main scaffold with top bar and content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    topBarContent: TopBarContent?,
    snackbarHostState: SnackbarHostState,
    authState: AuthState.Authenticated,
    availableAccounts: List<AccountData>,
    accountSwitchInProgress: Boolean,
    authViewModel: AuthViewModel,
    navController: NavController,
    scope: CoroutineScope,
    content: @Composable (PaddingValues) -> Unit
) {
    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(
            topBar = {
                MainTopAppBar(
                    topBarContent = topBarContent,
                    authState = authState,
                    availableAccounts = availableAccounts,
                    accountSwitchInProgress = accountSwitchInProgress,
                    authViewModel = authViewModel,
                    navController = navController,
                    scope = scope
                )
            },
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    val visualsWithError = data.visuals as? SnackbarVisualsWithError
                    SharedSnackbar(data, visualsWithError)
                }
            },
            content = content
        )
    }
}

/**
 * Top app bar with navigation items and user menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopAppBar(
    topBarContent: TopBarContent?,
    authState: AuthState.Authenticated,
    availableAccounts: List<AccountData>,
    accountSwitchInProgress: Boolean,
    authViewModel: AuthViewModel,
    navController: NavController,
    scope: CoroutineScope
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val userMenu = @Composable {
                    UserMenuButton(
                        authState = authState,
                        availableAccounts = availableAccounts,
                        accountSwitchInProgress = accountSwitchInProgress,
                        authViewModel = authViewModel,
                        navController = navController,
                        scope = scope
                    )
                }

                val navItems = buildNavItems(authState, navController, currentRoute)

                TopBarContentLayout(
                    topBarContent = topBarContent,
                    userMenu = userMenu,
                    navItems = navItems
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
    )
}

/**
 * User menu button with tooltip.
 */
@Composable
private fun UserMenuButton(
    authState: AuthState.Authenticated,
    availableAccounts: List<AccountData>,
    accountSwitchInProgress: Boolean,
    authViewModel: AuthViewModel,
    navController: NavController,
    scope: CoroutineScope
) {
    PlainTooltipBox(text = "User menu") {
        UserMenu(
            username = authState.username,
            availableAccounts = availableAccounts,
            accountSwitchInProgress = accountSwitchInProgress,
            onSwitchAccount = { authViewModel.openAccountSwitcher() },
            onAddAccount = { authViewModel.openAddAccount() },
            onLogout = { scope.launch { authViewModel.logout() } },
            onLogin = { navController.navigateToTop(Login) }
        )
    }
}

/**
 * Builds the list of navigation item composables.
 * Excludes the currently active route from the list.
 */
@Composable
private fun buildNavItems(
    authState: AuthState.Authenticated,
    navController: NavController,
    currentRoute: String?
): List<@Composable () -> Unit> = buildList {
    if (currentRoute != Chat.route) {
        add {
            NavIconButton(
                tooltip = "Chat",
                icon = Icons.AutoMirrored.Filled.Chat,
                onClick = { navController.navigateToTop(Chat) }
            )
        }
    }

    if (currentRoute != Settings.route) {
        add {
            NavIconButton(
                tooltip = "Settings",
                icon = Icons.Default.Settings,
                onClick = { navController.navigateToTop(Settings) }
            )
        }
    }

    if (currentRoute != Admin.route) {
        add {
            RequiresAnyPermission(
                authState = authState,
                permissions = listOf(CommonPermissions.MANAGE_USERS, CommonPermissions.MANAGE_ROLES)
            ) {
                NavIconButton(
                    tooltip = "Admin",
                    icon = Icons.Default.AdminPanelSettings,
                    onClick = { navController.navigateToTop(Admin) }
                )
            }
        }
    }
}

/**
 * Navigation icon button with tooltip.
 */
@Composable
private fun NavIconButton(
    tooltip: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    PlainTooltipBox(text = tooltip) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = tooltip
            )
        }
    }
}

/**
 * Renders the top bar content layout, using custom content if provided,
 * otherwise falling back to default layout.
 */
@Composable
private fun RowScope.TopBarContentLayout(
    topBarContent: TopBarContent?,
    userMenu: @Composable () -> Unit,
    navItems: List<@Composable () -> Unit>
) {
    if (topBarContent != null) {
        topBarContent(userMenu, navItems)
    } else {
        DefaultTopBarLayout(userMenu, navItems)
    }
}

/**
 * Default top bar layout with nav items on the left and user menu on the right.
 */
@Composable
private fun RowScope.DefaultTopBarLayout(
    userMenu: @Composable () -> Unit,
    navItems: List<@Composable () -> Unit>
) {
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.Start
    ) {
        navItems.forEach { item ->
            item()
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.End
    ) {
        userMenu()
    }
}

/**
 * Navigation host with all app routes.
 */
@Composable
private fun MainNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    sessionListViewModel: SessionListViewModel,
    authState: AuthState.Authenticated,
    authViewModel: AuthViewModel
) {
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        NavHost(
            navController = navController,
            startDestination = Chat
        ) {
            composable<Chat> {
                ChatScreen(
                    sessionListViewModel = sessionListViewModel,
                    authState = authState
                )
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
