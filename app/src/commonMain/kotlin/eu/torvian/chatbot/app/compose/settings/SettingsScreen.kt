package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.repository.AuthState

/**
 * A tabbed interface for the Settings screen UI.
 *
 * This screen provides a tabbed interface for managing:
 * - LLM Providers (E4.S8-S12, E5.S4) - Handled by ProvidersTabRoute
 * - LLM Models (E4.S1-S4) - Handled by ModelsTabRoute
 * - Model Settings Profiles (E4.S5-S6) - Handled by ModelSettingsConfigTabRoute
 * - Local MCP Servers (US6.4, US6.5) - Handled by LocalMCPServersTabRoute
 *
 * Each tab is now managed by its own Route component following the Route pattern
 * for better modularity, testability, and separation of concerns.
 */
@Composable
fun SettingsScreen(
    authState: AuthState.Authenticated
) {
    // Tab state management
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Providers", "Models", "Model Settings", "MCP Servers")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Tab navigation
        TabRow(
            selectedTabIndex = selectedTabIndex,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                )
            }
        }

        // Tab content - each route handles its own ViewModel and state management
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (selectedTabIndex) {
                0 -> ProvidersTabRoute(authState = authState)
                1 -> ModelsTabRoute(authState = authState)
                2 -> ModelSettingsConfigTabRoute(authState = authState)
                3 -> LocalMCPServersTabRoute(authState = authState)
            }
        }
    }
}
