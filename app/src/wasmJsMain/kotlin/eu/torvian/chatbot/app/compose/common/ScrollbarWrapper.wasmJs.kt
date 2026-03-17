package eu.torvian.chatbot.app.compose.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Web implementation of ScrollbarWrapper for lazy scrolling content.
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

/**
 * Web implementation of ScrollbarWrapper for non-lazy scroll containers.
 */
@Composable
actual fun ScrollbarWrapper(
    scrollState: ScrollState,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()

        VerticalScrollbar(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            adapter = rememberScrollbarAdapter(scrollState)
        )
    }
}

