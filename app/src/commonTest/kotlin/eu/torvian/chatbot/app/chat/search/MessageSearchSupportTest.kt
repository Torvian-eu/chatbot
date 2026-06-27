package eu.torvian.chatbot.app.chat.search

import eu.torvian.chatbot.app.testutils.data.assistantMessage
import eu.torvian.chatbot.app.testutils.data.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies pure search helper behavior used by the chat area UI.
 */
class MessageSearchSupportTest {
    /**
     * Ensures repeated matches become individual occurrence results in display order.
     */
    @Test
    fun findSearchMatches_returnsOrderedOccurrencesAcrossMessages() {
        val messages = listOf(
            userMessage(id = 1, sessionId = 1, content = "Alpha alpha"),
            assistantMessage(id = 2, sessionId = 1, content = "beta alpha"),
            userMessage(id = 3, sessionId = 1, content = "gamma"),
        )

        val result = findSearchMatches(messages, "alpha")

        assertEquals(
            listOf(
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 5),
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 1, startIndex = 6, endExclusive = 11),
                MessageSearchMatch(messageId = 2L, occurrenceIndexInMessage = 0, startIndex = 5, endExclusive = 10),
            ),
            result,
        )
    }

    /**
     * Confirms blank queries still disable search by returning no occurrences.
     */
    @Test
    fun findSearchMatches_returnsEmptyListForBlankQuery() {
        val result = findSearchMatches(
            messages = listOf(userMessage(id = 1, sessionId = 1, content = "Alpha alpha")),
            query = "   ",
        )

        assertEquals(emptyList(), result)
    }

    /**
     * Confirms search index normalization falls back to the first result when needed.
     */
    @Test
    fun normalizeSearchIndex_returnsFirstResultForInvalidSelection() {
        val result = normalizeSearchIndex(
            listOf(
                MessageSearchMatch(messageId = 10L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 3),
                MessageSearchMatch(messageId = 20L, occurrenceIndexInMessage = 0, startIndex = 1, endExclusive = 4),
            ),
            requestedIndex = 99,
        )

        assertEquals(0, result)
    }

    /**
     * Confirms navigation wraps around the available results.
     */
    @Test
    fun navigateSearchIndex_wrapsAroundResultSet() {
        val results = listOf(
            MessageSearchMatch(messageId = 10L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 3),
            MessageSearchMatch(messageId = 10L, occurrenceIndexInMessage = 1, startIndex = 4, endExclusive = 7),
            MessageSearchMatch(messageId = 30L, occurrenceIndexInMessage = 0, startIndex = 1, endExclusive = 4),
        )
        val forward = navigateSearchIndex(results, currentIndex = 2, direction = SearchDirection.FORWARD)
        val backward = navigateSearchIndex(results, currentIndex = 0, direction = SearchDirection.BACKWARD)

        assertEquals(0, forward)
        assertEquals(2, backward)
    }

    /**
     * Ensures match ranges are case-insensitive and non-overlapping.
     */
    @Test
    fun findSearchMatchRanges_returnsCaseInsensitiveNonOverlappingRanges() {
        val result = findSearchMatchRanges("Alpha alpha alphabet", "ALPHA")

        assertEquals(listOf(0..4, 6..10, 12..16), result)
    }
}
