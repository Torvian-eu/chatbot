package eu.torvian.chatbot.app.compose.common

import androidx.compose.ui.test.*
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LoadingOverlayTest {

    @Test
    fun loadingIndicator_showsContentAfterDelay() = runComposeUiTest {
        setContent {
            LoadingOverlay(delayMs = 500L)
        }

        // Advance time by the delay duration
        mainClock.advanceTimeBy(500L)

        // Assert that the content (spinner) IS displayed now
        onNodeWithContentDescription("Loading indicator").assertIsDisplayed()
    }

    @Test
    fun loadingIndicator_existsImmediatelyButContentNotVisible() = runComposeUiTest {
        setContent {
            LoadingOverlay(delayMs = 500L)
        }

        // Assert that the wrapper Box (with testTag) exists immediately
        onNodeWithTag(LOADING_OVERLAY_TAG).assertExists()

        // Assert that the inner content (spinner) is NOT displayed immediately
        onNodeWithContentDescription("Loading indicator").assertDoesNotExist()

        // Advance time by less than the delay
        mainClock.advanceTimeBy(400L)

        // Assert that content is still NOT displayed
        onNodeWithContentDescription("Loading indicator").assertDoesNotExist()
    }

}