package eu.torvian.chatbot.app.compose.topbar

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

/**
 * A helper composable to provide content to the TopBarContentController.
 * This composable manages the lifecycle of setting and clearing the top bar content
 * for the currently active screen.
 *
 * @param content The composable lambda to display in the top bar actions area.
 *                Pass null to clear the content.
 */
@Composable
fun TopBarContentProvider(
    content: TopBarContent?
) {
    val topBarController = LocalTopBarContent.current

    // Set the content whenever the 'content' lambda changes.
    // A composable lambda will typically be a new instance on every recomposition
    // if it captures changing state, causing this effect to re-run.
    LaunchedEffect(content) {
        if (content != null) {
            topBarController.setContent(content)
        } else {
            topBarController.clearContent()
        }
    }

    // Clear the content when this composable leaves the composition (e.g., screen changes).
    DisposableEffect(Unit) {
        onDispose {
            topBarController.clearContent()
        }
    }
}