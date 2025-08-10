package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Web implementation of ScrollbarWrapper that relies on browser-native scrollbars.
 * 
 * On web platforms, scrollbars are handled by the browser and can be styled
 * using CSS. This implementation wraps the content in a Box with the provided
 * modifier to ensure consistent layout behavior across platforms.
 * 
 * Note: Scrollbar appearance can be customized by adding CSS rules to your
 * index.html or stylesheet targeting ::-webkit-scrollbar selectors.
 * 
 * @param listState The LazyListState from the LazyColumn (not directly used on Web)
 * @param modifier Modifier to apply to the wrapper Box container
 * @param content The content to wrap, typically a LazyColumn
 */
@Composable
actual fun ScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    // On Web, scrollbars are handled by the browser and styled via CSS.
    // We apply the modifier to a Box that wraps the content to ensure
    // consistent layout behavior (size, padding, etc.) across platforms.
    Box(modifier = modifier) {
        content()
    }
}
