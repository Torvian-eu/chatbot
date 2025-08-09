package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Desktop implementation of ScrollbarWrapper that adds a visible VerticalScrollbar.
 * 
 * On desktop platforms, users expect to see visible scrollbars for better navigation
 * of long lists. This implementation uses Compose's VerticalScrollbar component
 * linked to the LazyColumn's state via rememberScrollbarAdapter.
 * 
 * @param listState The LazyListState from the LazyColumn to control scrollbar behavior
 * @param modifier Modifier to apply to the wrapper Box container
 * @param content The content to wrap, typically a LazyColumn
 */
@Composable
actual fun ScrollbarWrapper(
    listState: LazyListState,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        // Render the main content (LazyColumn)
        content()
        
        // Add the vertical scrollbar aligned to the right edge
        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            adapter = rememberScrollbarAdapter(listState)
        )
    }
}
