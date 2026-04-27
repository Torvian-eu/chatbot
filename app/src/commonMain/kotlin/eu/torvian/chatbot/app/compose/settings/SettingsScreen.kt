package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.repository.AuthState

/**
 * A sidebar-based settings shell with category-local breadcrumbs.
 *
 * Category selection is local to this shell, while each route composable keeps
 * ownership of its own loading, dialog, and business logic.
 */
@Composable
fun SettingsScreen(
    authState: AuthState.Authenticated
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.Providers) }
    var breadcrumbSegments by remember { mutableStateOf(listOf("Settings", selectedCategory.displayLabel)) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsSidebar(
            selectedCategory = selectedCategory,
            onCategorySelected = { category ->
                selectedCategory = category
                breadcrumbSegments = listOf("Settings", category.displayLabel)
            }
        )

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            SettingsBreadcrumbs(
                segments = breadcrumbSegments
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedCategory) {
                    SettingsCategory.Providers -> ProvidersTabRoute(
                        authState = authState,
                    ) { breadcrumbs ->
                        breadcrumbSegments = breadcrumbs
                    }
                    SettingsCategory.Models -> ModelsTabRoute(authState = authState)
                    SettingsCategory.ModelSettings -> ModelSettingsConfigTabRoute(authState = authState)
                    SettingsCategory.McpServers -> LocalMCPServersTabRoute(authState = authState)
                }
            }
        }
    }
}
