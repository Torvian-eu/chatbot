package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific scrollbar wrapper for lazy scrolling content.
 *
 * This composable provides a consistent interface for adding scrollbars to lazy list content
 * across different platforms:
 * - Desktop: Shows a visible VerticalScrollbar
 * - Android: Uses native system scrollbars (automatically handled)
 * - Web: Uses browser-native scrollbars (styled via CSS)
 *
 * @param listState The LazyListState from the LazyColumn to control scrollbar behavior
 * @param modifier Modifier to apply to the wrapper container (size, padding, etc.)
 * @param content The content to wrap, typically a LazyColumn
 */
@Composable
expect fun ScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)

/**
 * Platform-specific scrollbar wrapper for non-lazy scrolling content.
 *
 * This overload supports regular scroll containers such as a [Column] using [ScrollState].
 *
 * @param scrollState The ScrollState from the scrollable container to control scrollbar behavior
 * @param modifier Modifier to apply to the wrapper container (size, padding, etc.)
 * @param content The content to wrap, typically a vertically scrollable Column
 */
@Composable
expect fun ScrollbarWrapper(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
)
