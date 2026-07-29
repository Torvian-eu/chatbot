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
     * Confirms queries shorter than [MIN_QUERY_LENGTH] produce no results.
     */
    @Test
    fun findSearchMatches_returnsEmptyListForVeryShortQuery() {
        val result = findSearchMatches(
            messages = listOf(userMessage(id = 1, sessionId = 1, content = "a b c d e f g")),
            query = "a",
        )

        assertEquals(emptyList(), result)
    }

    /**
     * Confirms case-insensitive matching and non-overlapping results via [findSearchMatches].
     */
    @Test
    fun findSearchMatches_returnsCaseInsensitiveNonOverlappingMatches() {
        val messages = listOf(
            userMessage(id = 1, sessionId = 1, content = "Alpha alpha alphabet"),
        )

        val result = findSearchMatches(messages, "ALPHA")

        assertEquals(
            listOf(
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 5),
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 1, startIndex = 6, endExclusive = 11),
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 2, startIndex = 12, endExclusive = 17),
            ),
            result,
        )
    }

    /**
     * Confirms [occurrenceIndexInMessage] increments correctly within each message
     * and resets between messages.
     */
    @Test
    fun findSearchMatches_occurrenceIndexIsPerMessage() {
        val messages = listOf(
            userMessage(id = 1, sessionId = 1, content = "foo foo"),
            assistantMessage(id = 2, sessionId = 1, content = "foo"),
        )

        val result = findSearchMatches(messages, "foo")

        assertEquals(
            listOf(
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 3),
                MessageSearchMatch(messageId = 1L, occurrenceIndexInMessage = 1, startIndex = 4, endExclusive = 7),
                MessageSearchMatch(messageId = 2L, occurrenceIndexInMessage = 0, startIndex = 0, endExclusive = 3),
            ),
            result,
        )
    }

    /**
     * Confirms [DEFAULT_MAX_MATCHES] stops collecting additional results.
     */
    @Test
    fun findSearchMatches_respectsMaxMatchesCap() {
        val content = "x " + "y ".repeat(200)
        val messages = listOf(
            userMessage(id = 1, sessionId = 1, content = content),
        )

        val result = findSearchMatches(messages, "y ", maxMatches = 5)

        assertEquals(5, result.size)
        result.forEachIndexed { index, match ->
            assertEquals(index.toLong(), match.occurrenceIndexInMessage.toLong())
            assertEquals(1, match.messageId)
        }
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
}
