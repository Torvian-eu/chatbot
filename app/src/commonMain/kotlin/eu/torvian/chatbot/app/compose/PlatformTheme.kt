package eu.torvian.chatbot.app.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Platform-specific theme wrapper that provides platform-specific theming features.
 *
 * On Desktop and Web, this provides scrollbar styling via LocalScrollbarStyle.
 * On Android, this is a no-op since scrollbars are handled by the system.
 *
 * @param colorScheme The Material 3 color scheme to use for platform-specific styling.
 * @param content The composable content to be wrapped.
 */
@Composable
expect fun PlatformTheme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit
)
