package eu.torvian.chatbot.app.compose.topbar

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Type alias for the top bar content lambda.
 * This composable receives the user menu and navigation items as parameters,
 * allowing screens to customize their layout.
 */
typealias TopBarContent = @Composable RowScope.(
    userMenu: @Composable () -> Unit,
    navItems: List<@Composable () -> Unit>
) -> Unit

/**
 * Controller for managing dynamic top bar content.
 * Screens can use this to provide their own actions/controls in the top bar.
 */
interface TopBarContentController {
    /**
     * Set the content to display in the top bar actions area.
     * @param content A composable lambda with RowScope receiver for top bar actions.
     */
    fun setContent(content: TopBarContent)

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
