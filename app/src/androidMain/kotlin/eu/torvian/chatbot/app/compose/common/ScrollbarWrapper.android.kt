package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android implementation of ScrollbarWrapper that relies on native system scrollbars.
 * 
 * On Android, scrollbars are automatically handled by the system and appear as
 * subtle indicators that fade in/out during scrolling. This implementation simply
 * wraps the content in a Box with the provided modifier to ensure consistent
 * layout behavior across platforms.
 * 
 * @param listState The LazyListState from the LazyColumn (not directly used on Android)
 * @param modifier Modifier to apply to the wrapper Box container
 * @param content The content to wrap, typically a LazyColumn
 */
@Composable
actual fun ScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    // On Android, scrollbars are automatically handled by the system.
    // We apply the modifier to a Box that wraps the content to ensure
    // consistent layout behavior (size, padding, etc.) across platforms.
    Box(modifier = modifier) {
        content()
    }
}
