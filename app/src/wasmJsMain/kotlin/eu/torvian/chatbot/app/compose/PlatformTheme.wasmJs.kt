package eu.torvian.chatbot.app.compose

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * Web implementation of [PlatformTheme] that provides scrollbar styling.
 *
 * On web platforms, scrollbars are visible UI elements that need to be styled
 * to match the current theme. This implementation uses [LocalScrollbarStyle] to
 * provide theme-aware scrollbar colors.
 *
 * @param colorScheme The Material 3 color scheme to derive scrollbar colors from.
 * @param content The composable content to be wrapped.
 */
@Composable
actual fun PlatformTheme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalScrollbarStyle provides createScrollbarStyle(colorScheme)
    ) {
        content()
    }
}

/**
 * Creates a scrollbar style that adapts to the given color scheme.
 *
 * The scrollbar uses semi-transparent versions of the onSurface color to ensure
 * visibility in both light and dark modes.
 *
 * @param colorScheme The Material 3 color scheme to derive scrollbar colors from.
 * @return A [ScrollbarStyle] configured for the theme.
 */
private fun createScrollbarStyle(colorScheme: ColorScheme) =
    ScrollbarStyle(
        unhoverColor = colorScheme.onSurface.copy(alpha = 0.2f),
        hoverColor = colorScheme.onSurface.copy(alpha = 0.5f),
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300
    )
