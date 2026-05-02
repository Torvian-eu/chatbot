package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.PlainTooltipBox

/**
 * Top bar content for the Settings screen.
 * Displays a toggle button for the sidebar and navigation items.
 *
 * This composable is designed to work within a RowScope (app bar actions).
 *
 * @param userMenu The user menu composable to display on the right side.
 * @param navItems The list of navigation items to display.
 * @param isSidebarCollapsed Whether the settings sidebar is currently collapsed.
 * @param onToggleSidebar Callback to toggle the sidebar visibility.
 */
@Composable
fun RowScope.SettingsTopBarContent(
    userMenu: @Composable () -> Unit,
    navItems: List<@Composable () -> Unit>,
    isSidebarCollapsed: Boolean,
    onToggleSidebar: () -> Unit
) {
    // Left-aligned actions
    Row(
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Settings sidebar toggle button
        PlainTooltipBox(
            text = if (isSidebarCollapsed) "Show settings categories" else "Hide settings categories"
        ) {
            IconButton(
                onClick = onToggleSidebar,
                modifier = Modifier
                    .size(48.dp)
                    .then(
                        if (isSidebarCollapsed) Modifier.rotate(180f) else Modifier
                    )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuOpen,
                    contentDescription = if (isSidebarCollapsed) "Show settings categories" else "Hide settings categories"
                )
            }
        }

        // Navigation items
        navItems.forEach {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }

    // Spacer to push user menu to the right
    Spacer(Modifier.weight(1f))

    // User menu on the right
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(8.dp))
        userMenu()
    }
}
