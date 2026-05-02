package eu.torvian.chatbot.app.compose.topbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember

/**
 * A helper composable to provide content to the TopBarContentController.
 * This composable manages the lifecycle of setting and clearing the top bar content
 * for the currently active screen using a generation-based approach to prevent race conditions.
 *
 * @param content The composable lambda to display in the top bar actions area.
 *                Pass null to clear the content.
 */
@Composable
fun TopBarContentProvider(
    content: TopBarContent?
) {
    val topBarController = LocalTopBarContent.current

    // Track the generation handle for this specific provider instance
    val generationRef = remember { mutableIntStateOf(-1) }

    DisposableEffect(content) {
        if (content != null) {
            generationRef.value = topBarController.setContent(content)
        } else {
            // If content became null, clear our previous generation if any
            if (generationRef.value != -1) {
                topBarController.clearContent(generationRef.value)
                generationRef.value = -1
            }
        }

        onDispose {
            // Only clear if we had set content and it hasn't been overwritten
            if (generationRef.value != -1) {
                topBarController.clearContent(generationRef.value)
            }
        }
    }
}