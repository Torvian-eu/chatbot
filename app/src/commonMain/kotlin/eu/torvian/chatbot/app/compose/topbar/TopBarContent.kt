package eu.torvian.chatbot.app.compose.topbar

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Controller for managing dynamic top bar content.
 * Screens can use this to provide their own actions/controls in the top bar.
 */
interface TopBarContentController {
    /**
     * Set the content to display in the top bar actions area.
     * @param content A composable lambda with RowScope receiver for top bar actions.
     */
    fun setContent(content: @Composable RowScope.() -> Unit)

    /**
     * Clear the top bar content (show nothing in the actions area).
     */
    fun clearContent()
}

/**
 * CompositionLocal for accessing the TopBarContentController.
 * Screens should use this to set their top bar content.
 */
val LocalTopBarContent = compositionLocalOf<TopBarContentController> {
    error("TopBarContentController not provided")
}

