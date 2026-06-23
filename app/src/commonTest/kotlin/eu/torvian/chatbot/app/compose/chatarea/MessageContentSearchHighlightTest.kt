package eu.torvian.chatbot.app.compose.chatarea

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies occurrence-level search overlay styling for rendered message content.
 */
class MessageContentSearchHighlightTest {
    /**
     * Ensures the selected occurrence gets a distinct highlight span while other matches remain highlighted.
     */
    @Test
    fun buildSearchHighlightSpans_marksSelectedOccurrenceSeparately() {
        val matches = listOf(
            MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 5),
            MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 1, startIndex = 6, endExclusive = 11),
        )

        val spans = buildSearchHighlightSpans(
            searchMatches = matches,
            selectedSearchMatch = matches[1],
            regularHighlightColor = Color.Yellow,
            selectedHighlightColor = Color.Green,
            maxEndExclusive = 11,
        )

        assertEquals(2, spans.size)
        assertEquals(Color.Yellow, spans[0].item.background)
        assertEquals(0, spans[0].start)
        assertEquals(5, spans[0].end)
        assertEquals(Color.Green, spans[1].item.background)
        assertEquals(6, spans[1].start)
        assertEquals(11, spans[1].end)
    }

    /**
     * Ensures preview rendering ignores matches that fall outside the visible text prefix.
     */
    @Test
    fun buildSearchHighlightSpans_omitsMatchesOutsideRenderedRange() {
        val matches = listOf(
            MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 0, startIndex = 2, endExclusive = 5),
            MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 1, startIndex = 8, endExclusive = 11),
        )

        val spans = buildSearchHighlightSpans(
            searchMatches = matches,
            selectedSearchMatch = matches[1],
            regularHighlightColor = Color.Yellow,
            selectedHighlightColor = Color.Green,
            maxEndExclusive = 6,
        )

        assertEquals(1, spans.size)
        assertEquals(Color.Yellow, spans[0].item.background)
        assertEquals(2, spans[0].start)
        assertEquals(5, spans[0].end)
    }
}