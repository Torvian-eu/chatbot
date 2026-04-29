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
     * Sets the content and returns a generation handle.
     * Store this handle to later clear content only if still current.
     * @param content A composable lambda with RowScope receiver for top bar actions.
     * @return A generation ID that can be used to verify this content is still current.
     */
    fun setContent(content: TopBarContent): Int

    /**
     * Clears content only if the provided generation matches the current content's generation.
     * @param generation The generation ID returned by setContent.
     */
    fun clearContent(generation: Int)
}

/**
 * CompositionLocal for accessing the TopBarContentController.
 * Screens should use this to set their top bar content.
 */
val LocalTopBarContent = compositionLocalOf<TopBarContentController> {
    error("TopBarContentController not provided")
}
