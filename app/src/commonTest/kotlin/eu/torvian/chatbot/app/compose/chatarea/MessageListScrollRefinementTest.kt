package eu.torvian.chatbot.app.compose.chatarea

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the pure scroll refinement helper used by occurrence-based search navigation.
 */
class MessageListScrollRefinementTest {
    /**
     * Ensures refinement centers the selected occurrence when enough scroll range is available.
     */
    @Test
    fun computeSearchRefinementScrollTarget_centersSelectedOccurrence() {
        val result = computeSearchRefinementScrollTarget(
            selectedOccurrenceCenterYInContent = 550f,
            viewportHeight = 400,
            maxScroll = 1200,
        )

        assertEquals(350, result)
    }

    /**
     * Ensures refinement does not scroll above the start of the content.
     */
    @Test
    fun computeSearchRefinementScrollTarget_clampsToTopBoundary() {
        val result = computeSearchRefinementScrollTarget(
            selectedOccurrenceCenterYInContent = 120f,
            viewportHeight = 400,
            maxScroll = 1200,
        )

        assertEquals(0, result)
    }

    /**
     * Ensures refinement respects the available maximum scroll range near the bottom.
     */
    @Test
    fun computeSearchRefinementScrollTarget_clampsToBottomBoundary() {
        val result = computeSearchRefinementScrollTarget(
            selectedOccurrenceCenterYInContent = 1180f,
            viewportHeight = 400,
            maxScroll = 800,
        )

        assertEquals(800, result)
    }
}