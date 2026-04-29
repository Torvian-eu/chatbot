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
 *
 * A reset signal is incremented on every sidebar click (including re-selecting
 * the already active category) so that tab routes can clear their selected item
 * and return to the list view. Selection state lives in the ViewModels, not in
 * local {@code rememberSaveable} state.
 */
@Composable
fun SettingsScreen(
    authState: AuthState.Authenticated
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.Providers) }
    var breadcrumbSegments by remember { mutableStateOf(listOf("Settings", selectedCategory.displayLabel)) }
    // Incremented on every sidebar click so routes can reset to list view on re-selection.
    var categoryResetSignal by rememberSaveable { mutableStateOf(0) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsSidebar(
            selectedCategory = selectedCategory,
            onCategorySelected = { category ->
                selectedCategory = category
                categoryResetSignal++ // bump signal even when the same category is tapped
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
                        categoryResetSignal = categoryResetSignal
                    ) { breadcrumbs ->
                        breadcrumbSegments = breadcrumbs
                    }

                    SettingsCategory.Models -> ModelsTabRoute(
                        authState = authState,
                        categoryResetSignal = categoryResetSignal
                    ) { breadcrumbs ->
                        breadcrumbSegments = breadcrumbs
                    }

                    SettingsCategory.ModelSettings -> ModelSettingsConfigTabRoute(
                        authState = authState,
                        categoryResetSignal = categoryResetSignal
                    ) { breadcrumbs ->
                        breadcrumbSegments = breadcrumbs
                    }

                    SettingsCategory.McpServers -> LocalMCPServersTabRoute(
                        authState = authState,
                        categoryResetSignal = categoryResetSignal
                    ) { breadcrumbs ->
                        breadcrumbSegments = breadcrumbs
                    }
                }
            }
        }
    }
}
