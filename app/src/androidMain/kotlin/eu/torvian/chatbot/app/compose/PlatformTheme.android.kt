package eu.torvian.chatbot.app.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Android implementation of [PlatformTheme] that is a no-op.
 *
 * On Android, scrollbars are automatically handled by the system and appear as
 * subtle indicators that fade in/out during scrolling. No additional styling
 * is needed, so this implementation simply wraps the content without modification.
 *
 * @param colorScheme The Material 3 color scheme (not used on Android).
 * @param content The composable content to be wrapped.
 */
@Composable
actual fun PlatformTheme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit
) {
    // On Android, scrollbars are automatically handled by the system.
    // No additional styling is needed.
    content()
}
